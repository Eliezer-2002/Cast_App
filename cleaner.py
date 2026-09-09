import json
import re

# Define your file names
input_file_path = "gk_songs.json"  # Replace with your actual input file name
output_file_path = "songs_cleaned.json"  # The new modified file

# 1. Load the original JSON data
with open(input_file_path, "r", encoding="utf-8") as file:
    songs_data = json.load(file)

# 2. Loop through each item and modify the title
for song in songs_data:
    if "title" in song:
        # Removes numbers, dots, and spaces from the start of the string
        song["title"] = re.sub(r"^\d+\.\s*", "", song["title"])

# 3. Save the modified data into a new JSON file
with open(output_file_path, "w", encoding="utf-8") as file:
    # ensure_ascii=False preserves the Tamil script layout without breaking it into unicode codes
    json.dump(songs_data, file, ensure_ascii=False, indent=2)

print(f"Success! Modified JSON saved to: {output_file_path}")
