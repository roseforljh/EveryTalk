with open('main.py', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# The problematic area is around line 567-573
# Fix indentation for these lines
corrected_lines = [
    '                        # Process response based on provider\n',
    '                        if request_data.provider == "openai":\n',
    '                            async for event in process_openai_response(parsed_sse_data, state, request_id):\n',
    '                                yield event\n',
    '                        elif request_data.provider == "google":\n',
    '                            async for event in process_google_response(parsed_sse_data, state, request_id):\n',
    '                                yield event\n',
]

# Replace the lines
lines[567:574] = corrected_lines

# Write the fixed content back to a new file
with open('main.py.fixed', 'w', encoding='utf-8') as f:
    f.writelines(lines)

print("Fixed file written to main.py.fixed") 