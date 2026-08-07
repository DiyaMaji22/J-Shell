package com.devops;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits shell input into tokens, respecting single and double quotes.
 * echo "hello world" -> ["echo", "hello world"]
 * echo 'it\'s fine'  -> ["echo", "it's fine"]  (no escape support inside single quotes)
 */
public final class Tokenizer {

    private Tokenizer() {}

    public static String[] tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDouble = false;
        boolean inSingle = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == ' ' && !inDouble && !inSingle) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }

        return tokens.toArray(String[]::new);
    }
}
