#!/bin/bash

# SAFE fix script - only modifies specific lines, no global sed replacements

ERROR_FILE="${1:-error.txt}"

echo "🔧 Applying specific fixes from $ERROR_FILE..."

while IFS= read -r line; do
    # Skip non-error lines
    [[ ! "$line" =~ \.kt:[0-9]+ ]] && continue
    
    # Extract filename and line number
    if [[ "$line" =~ /([^/]+\.kt):([0-9]+): ]]; then
        file="${BASH_REMATCH[1]}"
        line_num="${BASH_REMATCH[2]}"
    else
        continue
    fi
    
    # Skip if file doesn't exist
    [ ! -f "$file" ] && continue
    
    # Backup the original line before modifying
    original_line=$(sed -n "${line_num}p" "$file")
    
    # 1. Fix tabs (replace with 4 spaces)
    if echo "$line" | grep -q "Unexpected tab"; then
        echo "  $file:$line_num - replacing tabs"
        sed -i "${line_num}s/\t/    /g" "$file"
    fi
    
    # 2. Fix indentation level (only if the error says "should be X")
    if echo "$line" | grep -q "should be ([0-9]\+)"; then
        expected=$(echo "$line" | grep -o "should be [0-9]\+" | grep -o "[0-9]\+")
        echo "  $file:$line_num - setting indent to $expected spaces"
        # Only change leading whitespace, keep the rest of the line
        sed -i "${line_num}s/^[[:space:]]*/$(printf '%*s' $expected '')/" "$file"
    fi
    
    # 3. Add trailing comma before ) - ONLY on lines that end with )
    if echo "$line" | grep -q "Missing trailing comma"; then
        if sed -n "${line_num}p" "$file" | grep -q ')$'; then
            echo "  $file:$line_num - adding trailing comma"
            sed -i "${line_num}s/)/,)/" "$file"
        fi
    fi
    
    # 4. Split arguments - ONLY if line has multiple args and is long
    if echo "$line" | grep -q "Argument should be on a separate line"; then
        line_content=$(sed -n "${line_num}p" "$file")
        # Only split if line has commas and is not already split
        if echo "$line_content" | grep -q "," && ! echo "$line_content" | grep -q "^[[:space:]]*[a-zA-Z0-9_]*($"; then
            echo "  $file:$line_num - splitting arguments (manual fix may be needed)"
            # Simple split: replace first comma with newline+indent
            sed -i "${line_num}s/, /,\n        /" "$file"
        fi
    fi
    
done < "$ERROR_FILE"

# Verify files still have content
echo ""
echo "🔍 Verifying files..."
for f in DataModel.kt Hanime.kt; do
    if [ -f "$f" ]; then
        size=$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f" 2>/dev/null)
        if [ "$size" -eq 0 ]; then
            echo "❌ WARNING: $f is empty! Restore from backup immediately!"
        else
            echo "✅ $f is intact ($size bytes)"
        fi
    fi
done

echo ""
echo "Done. Run your build to check if errors remain."