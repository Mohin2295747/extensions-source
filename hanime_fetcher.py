import hashlib
import time
import json
import requests
from urllib.parse import urlparse

def test_signature_formats_with_token(time_value, token):
    """Test different signature formats including the token"""
    
    formats = [
        (f"c1{{{time_value}}}", "time only with braces"),
        (f"c1{time_value}", "time only no braces"),
        (f"c1{{{time_value}}}{token}", "time + token with braces"),
        (f"c1{time_value}{token}", "time + token no braces"),
        (f"c1{{{time_value}}}{{{token}}}", "time and token both braced"),
        (f"{token}c1{{{time_value}}}", "token then time"),
        (f"c1{time_value}_{token}", "time_token with underscore"),
        (f"c1-{time_value}-{token}", "time-token with dash"),
        (f"c1|{time_value}|{token}", "time|token with pipe"),
        (f"web2{time_value}{token}", "web2 prefix"),
        (f"web2{{{time_value}}}{{{token}}}", "web2 with braces"),
    ]
    
    print("\n🔍 Testing signature formats with token:")
    print("=" * 70)
    print(f"Token: {token}")
    print(f"Time: {time_value}")
    print("-" * 70)
    
    for base_string, description in formats:
        signature = hashlib.sha256(base_string.encode()).hexdigest()
        print(f"{description:30} | {signature[:20]}...")
    
    return None

def generate_signature_with_token(time_value, token):
    """Generate signature using the token"""
    # Based on the JS structure, the format might be c1{time}{token}
    base_string = f"c1{{{time_value}}}{token}"
    return hashlib.sha256(base_string.encode()).hexdigest()

def get_video_id_from_slug(slug):
    """Get numeric video ID"""
    url = f"https://hanime.tv/api/v8/video?id={slug}"
    headers = {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36',
        'Accept': 'application/json',
        'Referer': 'https://hanime.tv/',
        'Origin': 'https://hanime.tv'
    }
    
    try:
        response = requests.get(url, headers=headers, timeout=10)
        if response.status_code == 200:
            data = response.json()
            if 'hentai_video' in data and data['hentai_video']:
                return data['hentai_video'].get('id')
        return None
    except Exception as e:
        print(f"Error getting video ID: {e}")
        return None

def fetch_video_manifest_with_token(video_id, token):
    """Fetch video manifest using the extracted token"""
    current_time = int(time.time())
    signature = generate_signature_with_token(current_time, token)
    
    headers = {
        'authority': 'cached.freeanimehentai.net',
        'accept': 'application/json',
        'accept-language': 'en-GB,en-US;q=0.9,en;q=0.8',
        'origin': 'https://hanime.tv',
        'referer': 'https://hanime.tv/',
        'sec-ch-ua': '"Chromium";v="137", "Not/A)Brand";v="24"',
        'sec-ch-ua-mobile': '?1',
        'sec-ch-ua-platform': '"Android"',
        'user-agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36',
        'x-csrf-token': token,  # Using the token as CSRF token
        'x-license': '',
        'x-session-token': '',
        'x-signature': signature,
        'x-signature-version': 'web2',
        'x-time': str(current_time),
        'x-user-license': ''
    }
    
    url = f"https://cached.freeanimehentai.net/api/v8/guest/videos/{video_id}/manifest"
    print(f"\n🔍 Fetching manifest with token")
    print(f"⏰ Time: {current_time}")
    print(f"🎫 Token: {token}")
    print(f"🔑 Signature: {signature}")
    print(f"🆔 Video ID: {video_id}")
    print("-" * 50)
    
    session = requests.Session()
    response = session.get(url, headers=headers)
    return response

def extract_slug_from_url(url):
    """Extract video slug from hanime.tv URL"""
    path = urlparse(url).path
    if '/videos/hentai/' in path:
        return path.split('/videos/hentai/')[-1]
    return None

def main():
    video_url = "https://hanime.tv/videos/hentai/eroge-sex-game-make-sexy-games-2"
    token = "033afe4831c6415399baba9a25ef2c01"
    
    print("🎬 Hanime.tv Video Fetcher (With Token)")
    print("=" * 50)
    
    # Test different signature formats with the token
    test_signature_formats_with_token(1772354199, token)  # Using the working time from PowerShell
    
    # Get video ID
    slug = extract_slug_from_url(video_url)
    if not slug:
        print("❌ Could not extract slug from URL")
        return
    
    print(f"\n📺 Slug: {slug}")
    print("🔄 Getting numeric video ID...")
    
    video_id = get_video_id_from_slug(slug)
    if not video_id:
        video_id = 173  # Fallback to known ID
        print(f"🔧 Using known ID: {video_id}")
    else:
        print(f"✅ Found ID: {video_id}")
    
    # Try multiple attempts with different timestamps
    print("\n🔄 Attempting to fetch manifest...")
    
    for attempt in range(3):
        print(f"\n📝 Attempt {attempt + 1}")
        response = fetch_video_manifest_with_token(video_id, token)
        
        print(f"📡 Status Code: {response.status_code}")
        
        if response.status_code == 200:
            try:
                data = response.json()
                print("✅ Success! Video streams found:")
                print("=" * 50)
                
                # Save to file
                with open('manifest_success.json', 'w') as f:
                    json.dump(data, f, indent=2)
                print("📁 Response saved to manifest_success.json")
                
                # Display streams
                if 'videos_manifest' in data and data['videos_manifest']:
                    for server in data['videos_manifest'].get('servers', []):
                        print(f"\n📡 Server: {server.get('name', 'Unknown')}")
                        for stream in server.get('streams', []):
                            if stream.get('is_guest_allowed'):
                                print(f"  • {stream.get('height', 'unknown')}p")
                                print(f"    URL: {stream.get('url', 'no url')}")
                break
            except json.JSONDecodeError:
                print("❌ Response is not valid JSON")
        else:
            try:
                error_data = response.json()
                print(f"❌ Error: {error_data}")
            except:
                print(f"❌ Raw response: {response.text[:200]}")
            
            if attempt < 2:
                print("⏳ Waiting before retry...")
                time.sleep(2)

if __name__ == "__main__":
    main()
