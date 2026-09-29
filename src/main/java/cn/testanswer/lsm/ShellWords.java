package cn.testanswer.lsm;

import java.util.ArrayList;
import java.util.List;

final class ShellWords {
    private ShellWords() { }

    static List<String> parse(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == '"' && c == '\\') {
                if (++i == line.length()) { throw new IllegalArgumentException("Trailing escape in quoted text"); }
                char next = line.charAt(i);
                switch (next) {
                    case 'n' -> word.append('\n');
                    case 'r' -> word.append('\r');
                    case 't' -> word.append('\t');
                    case '"', '\\' -> word.append(next);
                    default -> word.append('\\').append(next);
                }
            } else if (quote != 0) {
                if (c == quote) { quote = 0; } else { word.append(c); }
            } else if (c == '"' || c == '\'') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    words.add(word.toString());
                    word.setLength(0);
                    started = false;
                }
            } else {
                word.append(c);
                started = true;
            }
        }
        if (quote != 0) { throw new IllegalArgumentException("Unclosed quote"); }
        if (started) { words.add(word.toString()); }
        return words;
    }
}
