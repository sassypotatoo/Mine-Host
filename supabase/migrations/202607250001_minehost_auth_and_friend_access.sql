-- MineHost Supabase schema
-- Run with the Supabase CLI or SQL editor after reviewing it for your project.
-- No service-role key is required by the Android app. All client access is RLS protected.

create extension if not exists pgcrypto;

create table if not exists public.profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  display_name text,
  avatar_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  device_identifier text not null,
  display_name text not null,
  platform text not null default 'android' check (platform in ('android')),
  app_version text,
  public_key text,
  last_seen_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  unique(user_id, device_identifier)
);

create table if not exists public.minehost_servers (
  server_uuid uuid primary key,
  owner_id uuid not null references auth.users(id) on delete cascade,
  display_name text not null,
  engine_id text not null,
  engine_version text,
  device_id uuid references public.devices(id) on delete set null,
  remote_access_enabled boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.server_members (
  server_uuid uuid not null references public.minehost_servers(server_uuid) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('admin', 'operator', 'viewer')),
  invited_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key(server_uuid, user_id)
);

create table if not exists public.server_invitations (
  id uuid primary key default gen_random_uuid(),
  server_uuid uuid not null references public.minehost_servers(server_uuid) on delete cascade,
  invited_email text not null,
  role text not null check (role in ('admin', 'operator', 'viewer')),
  invited_by uuid not null references auth.users(id),
  status text not null default 'pending' check (status in ('pending', 'accepted', 'revoked', 'expired')),
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  accepted_at timestamptz,
  constraint invitation_expiry_after_creation check (expires_at > created_at)
);

create unique index if not exists server_invitations_one_pending_email
  on public.server_invitations(server_uuid, lower(invited_email))
  where status = 'pending';

create table if not exists public.remote_actions (
  id uuid primary key default gen_random_uuid(),
  server_uuid uuid not null references public.minehost_servers(server_uuid) on delete cascade,
  requested_by uuid not null references auth.users(id) on delete cascade,
  action_type text not null check (action_type in (
    'start_server', 'stop_server', 'restart_server', 'send_command',
    'view_status', 'view_players', 'view_logs'
  )),
  payload jsonb not null default '{}'::jsonb,
  confirmation_required boolean not null default false,
  confirmed_at timestamptz,
  status text not null default 'pending' check (status in (
    'pending', 'claimed', 'succeeded', 'failed', 'rejected', 'expired'
  )),
  result jsonb,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '10 minutes'),
  claimed_at timestamptz,
  completed_at timestamptz
);

create index if not exists remote_actions_pending_idx
  on public.remote_actions(server_uuid, created_at)
  where status = 'pending';

create table if not exists public.server_audit_log (
  id bigint generated always as identity primary key,
  server_uuid uuid not null references public.minehost_servers(server_uuid) on delete cascade,
  actor_id uuid references auth.users(id) on delete set null,
  event_type text not null,
  details jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

-- Security helper functions deliberately expose only a role string/boolean.
create or replace function public.minehost_server_role(p_server_uuid uuid)
returns text
language sql
stable
security definer
set search_path = public
as $$
  select case
    when s.owner_id = auth.uid() then 'owner'
    else (
      select m.role from public.server_members m
      where m.server_uuid = p_server_uuid and m.user_id = auth.uid()
    )
  end
  from public.minehost_servers s
  where s.server_uuid = p_server_uuid;
$$;

revoke all on function public.minehost_server_role(uuid) from public;
grant execute on function public.minehost_server_role(uuid) to authenticated;

create or replace function public.minehost_can_view(p_server_uuid uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select public.minehost_server_role(p_server_uuid) is not null;
$$;

create or replace function public.minehost_can_admin(p_server_uuid uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(public.minehost_server_role(p_server_uuid) in ('owner', 'admin'), false);
$$;

create or replace function public.minehost_can_request_action(
  p_server_uuid uuid,
  p_action_type text
)
returns boolean
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  current_role text := public.minehost_server_role(p_server_uuid);
begin
  if current_role is null then return false; end if;
  if p_action_type in ('view_status', 'view_players') then return true; end if;
  if p_action_type = 'view_logs' then return current_role in ('owner', 'admin', 'operator'); end if;
  if p_action_type = 'send_command' then return current_role in ('owner', 'admin', 'operator'); end if;
  if p_action_type in ('start_server', 'stop_server', 'restart_server') then
    return current_role in ('owner', 'admin');
  end if;
  return false;
end;
$$;

revoke all on function public.minehost_can_view(uuid) from public;
revoke all on function public.minehost_can_admin(uuid) from public;
revoke all on function public.minehost_can_request_action(uuid, text) from public;
grant execute on function public.minehost_can_view(uuid) to authenticated;
grant execute on function public.minehost_can_admin(uuid) to authenticated;
grant execute on function public.minehost_can_request_action(uuid, text) to authenticated;

-- Invitations are accepted through an RPC so clients cannot manufacture membership rows.
create or replace function public.accept_server_invitation(p_invitation_id uuid)
returns public.server_members
language plpgsql
security definer
set search_path = public
as $$
declare
  invitation public.server_invitations;
  member_row public.server_members;
  jwt_email text := lower(coalesce(auth.jwt() ->> 'email', ''));
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;

  select * into invitation
  from public.server_invitations
  where id = p_invitation_id
  for update;

  if invitation.id is null then raise exception 'Invitation not found'; end if;
  if invitation.status <> 'pending' then raise exception 'Invitation is no longer pending'; end if;
  if invitation.expires_at <= now() then
    update public.server_invitations set status = 'expired' where id = invitation.id;
    raise exception 'Invitation expired';
  end if;
  if lower(invitation.invited_email) <> jwt_email then
    raise exception 'Invitation email does not match the signed-in account';
  end if;

  insert into public.server_members(server_uuid, user_id, role, invited_by)
  values(invitation.server_uuid, auth.uid(), invitation.role, invitation.invited_by)
  on conflict(server_uuid, user_id) do update
    set role = excluded.role, updated_at = now()
  returning * into member_row;

  update public.server_invitations
  set status = 'accepted', accepted_at = now()
  where id = invitation.id;

  insert into public.server_audit_log(server_uuid, actor_id, event_type, details)
  values(invitation.server_uuid, auth.uid(), 'invitation_accepted',
         jsonb_build_object('invitation_id', invitation.id, 'role', invitation.role));

  return member_row;
end;
$$;

revoke all on function public.accept_server_invitation(uuid) from public;
grant execute on function public.accept_server_invitation(uuid) to authenticated;

-- Remote action creation and confirmation are RPC-only. Clients cannot set
-- requested_by, confirmation flags, timestamps, status, or result columns directly.
create or replace function public.request_remote_action(
  p_server_uuid uuid,
  p_action_type text,
  p_payload jsonb default '{}'::jsonb
)
returns public.remote_actions
language plpgsql
security definer
set search_path = public
as $$
declare
  action_row public.remote_actions;
  is_destructive boolean := p_action_type in ('stop_server', 'restart_server');
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  if not public.minehost_can_request_action(p_server_uuid, p_action_type) then
    raise exception 'Action is not allowed for this server role';
  end if;
  if jsonb_typeof(coalesce(p_payload, '{}'::jsonb)) <> 'object' then
    raise exception 'Action payload must be a JSON object';
  end if;

  insert into public.remote_actions(
    server_uuid, requested_by, action_type, payload,
    confirmation_required, confirmed_at, status, expires_at
  ) values (
    p_server_uuid, auth.uid(), p_action_type, coalesce(p_payload, '{}'::jsonb),
    is_destructive, null, 'pending', now() + interval '10 minutes'
  ) returning * into action_row;
  return action_row;
end;
$$;

create or replace function public.confirm_remote_action(p_action_id uuid)
returns public.remote_actions
language plpgsql
security definer
set search_path = public
as $$
declare
  action_row public.remote_actions;
begin
  update public.remote_actions
  set confirmed_at = now()
  where id = p_action_id
    and requested_by = auth.uid()
    and confirmation_required = true
    and confirmed_at is null
    and status = 'pending'
    and expires_at > now()
  returning * into action_row;

  if action_row.id is null then
    raise exception 'Remote action cannot be confirmed';
  end if;
  return action_row;
end;
$$;

create or replace function public.complete_remote_action(
  p_action_id uuid,
  p_succeeded boolean,
  p_result jsonb default '{}'::jsonb
)
returns public.remote_actions
language plpgsql
security definer
set search_path = public
as $$
declare
  action_row public.remote_actions;
begin
  update public.remote_actions a
  set status = case when p_succeeded then 'succeeded' else 'failed' end,
      result = coalesce(p_result, '{}'::jsonb),
      completed_at = now()
  from public.minehost_servers s
  where a.id = p_action_id
    and a.server_uuid = s.server_uuid
    and s.owner_id = auth.uid()
    and a.status = 'claimed'
  returning a.* into action_row;

  if action_row.id is null then
    raise exception 'Remote action cannot be completed';
  end if;
  return action_row;
end;
$$;

revoke all on function public.request_remote_action(uuid, text, jsonb) from public;
revoke all on function public.confirm_remote_action(uuid) from public;
revoke all on function public.complete_remote_action(uuid, boolean, jsonb) from public;
grant execute on function public.request_remote_action(uuid, text, jsonb) to authenticated;
grant execute on function public.confirm_remote_action(uuid) to authenticated;
grant execute on function public.complete_remote_action(uuid, boolean, jsonb) to authenticated;

-- Atomically claim one pending action. Only the authenticated server owner may
-- claim it, and expired/disabled queues are rejected inside the same transaction.
create or replace function public.claim_remote_action(p_action_id uuid)
returns public.remote_actions
language plpgsql
security definer
set search_path = public
as $$
declare
  action_row public.remote_actions;
begin
  update public.remote_actions a
  set status = 'claimed', claimed_at = now()
  from public.minehost_servers s
  where a.id = p_action_id
    and a.server_uuid = s.server_uuid
    and s.owner_id = auth.uid()
    and s.remote_access_enabled = true
    and a.status = 'pending'
    and a.expires_at > now()
    and (a.confirmation_required = false or a.confirmed_at is not null)
  returning a.* into action_row;

  if action_row.id is null then
    raise exception 'Remote action is unavailable or was already claimed';
  end if;
  return action_row;
end;
$$;

revoke all on function public.claim_remote_action(uuid) from public;
grant execute on function public.claim_remote_action(uuid) to authenticated;

-- Enable RLS on every user-controlled table.
alter table public.profiles enable row level security;
alter table public.devices enable row level security;
alter table public.minehost_servers enable row level security;
alter table public.server_members enable row level security;
alter table public.server_invitations enable row level security;
alter table public.remote_actions enable row level security;
alter table public.server_audit_log enable row level security;

-- Profiles
drop policy if exists profiles_select_self on public.profiles;
create policy profiles_select_self on public.profiles for select
  using (user_id = auth.uid());
drop policy if exists profiles_insert_self on public.profiles;
create policy profiles_insert_self on public.profiles for insert
  with check (user_id = auth.uid());
drop policy if exists profiles_update_self on public.profiles;
create policy profiles_update_self on public.profiles for update
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Devices
drop policy if exists devices_owner_all on public.devices;
create policy devices_owner_all on public.devices for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Server records
drop policy if exists servers_visible_to_members on public.minehost_servers;
create policy servers_visible_to_members on public.minehost_servers for select
  using (public.minehost_can_view(server_uuid));
drop policy if exists servers_owner_insert on public.minehost_servers;
create policy servers_owner_insert on public.minehost_servers for insert
  with check (owner_id = auth.uid());
drop policy if exists servers_owner_update on public.minehost_servers;
create policy servers_owner_update on public.minehost_servers for update
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());
drop policy if exists servers_owner_delete on public.minehost_servers;
create policy servers_owner_delete on public.minehost_servers for delete
  using (owner_id = auth.uid());

-- Memberships
drop policy if exists members_visible_to_server on public.server_members;
create policy members_visible_to_server on public.server_members for select
  using (public.minehost_can_view(server_uuid));
-- Direct client inserts are intentionally forbidden. Membership is created only
-- through accept_server_invitation(), which verifies the recipient email and expiry.
drop policy if exists members_managed_by_admin on public.server_members;
drop policy if exists members_updated_by_admin on public.server_members;
create policy members_updated_by_admin on public.server_members for update
  using (public.minehost_can_admin(server_uuid))
  with check (public.minehost_can_admin(server_uuid) and role in ('admin', 'operator', 'viewer'));
drop policy if exists members_deleted_by_admin_or_self on public.server_members;
create policy members_deleted_by_admin_or_self on public.server_members for delete
  using (public.minehost_can_admin(server_uuid) or user_id = auth.uid());

-- Invitations
drop policy if exists invitations_visible_to_admin_or_recipient on public.server_invitations;
create policy invitations_visible_to_admin_or_recipient on public.server_invitations for select
  using (
    public.minehost_can_admin(server_uuid)
    or lower(invited_email) = lower(coalesce(auth.jwt() ->> 'email', ''))
  );
drop policy if exists invitations_created_by_admin on public.server_invitations;
create policy invitations_created_by_admin on public.server_invitations for insert
  with check (
    public.minehost_can_admin(server_uuid)
    and invited_by = auth.uid()
    and role in ('admin', 'operator', 'viewer')
    and expires_at <= now() + interval '30 days'
  );
drop policy if exists invitations_updated_by_admin on public.server_invitations;
create policy invitations_updated_by_admin on public.server_invitations for update
  using (public.minehost_can_admin(server_uuid))
  with check (public.minehost_can_admin(server_uuid));

-- Remote actions. No file-system action type exists by design.
drop policy if exists actions_visible_to_requester_or_server on public.remote_actions;
create policy actions_visible_to_requester_or_server on public.remote_actions for select
  using (
    requested_by = auth.uid()
    or exists (
      select 1 from public.minehost_servers s
      where s.server_uuid = remote_actions.server_uuid and s.owner_id = auth.uid()
    )
  );
drop policy if exists actions_insert_authorized on public.remote_actions;
-- No direct insert policy: request_remote_action() derives identity and confirmation state.
drop policy if exists actions_device_owner_update on public.remote_actions;
-- No direct update policy: claim_remote_action(), confirm_remote_action(), and
-- complete_remote_action() constrain every mutable column server-side.

-- Audit history is append-only for authenticated actors and visible to server members.
drop policy if exists audit_visible_to_members on public.server_audit_log;
create policy audit_visible_to_members on public.server_audit_log for select
  using (public.minehost_can_view(server_uuid));
drop policy if exists audit_insert_actor on public.server_audit_log;
create policy audit_insert_actor on public.server_audit_log for insert
  with check (actor_id = auth.uid() and public.minehost_can_view(server_uuid));

-- Keep timestamps current without trusting clients.
create or replace function public.minehost_touch_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists profiles_touch_updated_at on public.profiles;
create trigger profiles_touch_updated_at before update on public.profiles
for each row execute function public.minehost_touch_updated_at();
drop trigger if exists servers_touch_updated_at on public.minehost_servers;
create trigger servers_touch_updated_at before update on public.minehost_servers
for each row execute function public.minehost_touch_updated_at();
drop trigger if exists members_touch_updated_at on public.server_members;
create trigger members_touch_updated_at before update on public.server_members
for each row execute function public.minehost_touch_updated_at();
