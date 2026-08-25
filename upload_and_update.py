import os
import json
import subprocess
import time

def upload_and_update():
    jar_path = '/tmp/Nukkit-MOT/target/Nukkit-MOT-SNAPSHOT.jar'
    # Wait for the jar to exist and be fully written
    while not os.path.exists(jar_path):
        print("Waiting for jar to be built...")
        time.sleep(5)
    
    # get the size
    size = os.path.getsize(jar_path)
    
    # upload
    print("Uploading...")
    result = subprocess.run(
        ['curl', '-s', '-X', 'POST', '-F', f'files[]=@{jar_path}', 'https://uguu.se/upload.php'],
        capture_output=True, text=True
    )
    
    response = json.loads(result.stdout)
    url = response['files'][0]['url']
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

if __name__ == '__main__':
    upload_and_update()
