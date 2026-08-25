import os
import json
import subprocess
import time

def upload_and_update():
    jar_path = '/tmp/Nukkit-MOT/target/Nukkit-MOT-SNAPSHOT.jar'
    
    # get the size
    size = os.path.getsize(jar_path)
    
    # upload
    print("Uploading to litterbox...")
    result = subprocess.run(
        ['curl', '-s', '-F', 'reqtype=fileupload', '-F', 'time=72h', '-F', f'fileToUpload=@{jar_path}', 'https://litterbox.catbox.moe/resources/internals/api.php'],
        capture_output=True, text=True
    )
    try:
        url = result.stdout.strip()
        if not url.startswith("https://"):
            print(f"Failed to get valid url: {url}")
            return
            
        print(f"Uploaded to {url}")
        
        # update json
        json_path = 'engine-metadata/nukkit-mot/last-known-good.json'
        with open(json_path, 'r') as f:
            data = json.load(f)
            
        data['resolvedDownloadUrl'] = url
        data['artifactSize'] = size
        
        with open(json_path, 'w') as f:
            json.dump(data, f, indent=2)
            
        print("Updated metadata.")
    except Exception as e:
        print(f"Failed: {e}")

if __name__ == '__main__':
    upload_and_update()
