import os
import json
import subprocess
import time

def upload_and_update():
    jar_path = '/tmp/Nukkit-MOT/target/Nukkit-MOT-SNAPSHOT.jar'
    
    # get the size
    size = os.path.getsize(jar_path)
    
    # upload
    print("Uploading to file.io...")
    result = subprocess.run(
        ['curl', '-s', '-F', f'file=@{jar_path}', 'https://file.io'],
        capture_output=True, text=True
    )
    
    try:
        response = json.loads(result.stdout)
        if response.get("success"):
            url = response['link']
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
        else:
            print("Failed:", result.stdout)
    except Exception as e:
        print("Failed:", e)
        print(result.stdout)
    
if __name__ == '__main__':
    upload_and_update()
