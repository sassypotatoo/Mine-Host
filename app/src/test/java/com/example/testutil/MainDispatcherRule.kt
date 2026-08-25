package com.example.testutil

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Installs an eager test dispatcher as Dispatchers.Main for the duration of a test.
 *
 * Robolectric executes tests on the main-looper thread itself. Production code that
 * suspends on Dispatchers.Main (e.g. JvmServerEngineBase.handleProcessExit) would
 * queue onto the same blocked looper thread inside runBlocking/runTest, deadlocking
 * irrecoverably because neither the looper nor the coroutine timeout can preempt the
 * busy thread. Overriding Main makes those dispatches eager on the caller thread.
 */
class MainDispatcherRule(
    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
