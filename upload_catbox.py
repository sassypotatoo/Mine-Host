import os
import json
import subprocess
import time

def upload_and_update():
    jar_path = '/tmp/Nukkit-MOT/target/Nukkit-MOT-SNAPSHOT.jar'
    
    # get the size
    size = os.path.getsize(jar_path)
    
    # upload
    print("Uploading to gofile...")
    result = subprocess.run(
        ['curl', '-s', 'https://api.gofile.io/getServer'],
        capture_output=True, text=True
    )
    try:
        server = json.loads(result.stdout)['data']['server']
        
        result2 = subprocess.run(
            ['curl', '-s', '-F', f'file=@{jar_path}', f'https://{server}.gofile.io/uploadFile'],
            capture_output=True, text=True
        )
        response = json.loads(result2.stdout)
        url = response['data']['downloadPage']
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
        print(result.stdout)
        if 'result2' in locals():
            print(result2.stdout)

if __name__ == '__main__':
    upload_and_update()
