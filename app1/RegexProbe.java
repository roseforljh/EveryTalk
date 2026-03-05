import java.util.regex.*;
public class RegexProbe {
  public static void main(String[] args) {
    String[][] ps = new String[][]{
      {"MULTIPLE_SPACES_REGEX", " {2,}"},
      {"ENUM_ITEM_REGEX", "(?<!\\n)\\s+([A-D])[\\.)]\\s"},
      {"WINDOWS_PATH_REGEX", "^[A-Za-z]:\\\\"},
      {"BASE64_IMAGE_PATTERN", "(\\![\\[^\\]]*\\]\\()\\s*(<?)(:?data:image\\/[^)>]+)(>?)\\s*(\\))"},
      {"FULL_WIDTH_PAREN_BOLD_REGEX", "（\\*\\*"},
      {"QUOTED_BOLD_PATTERN", "\\*\\*[\"“”'‘’「」『』](.+?)[\"“”'‘’「」『』]\\*\\*"},
      {"HEADER_SPACE_REGEX", "(?<=^|\\n)(#{1,6})(?=[^#\\s])"},
      {"LONG_HEADER_REGEX", "^(#{1,6})(?=\\s.{50,})"},
      {"MULTILINE_BLOCK_DOLLAR_PATTERN", "\\[double dollar]\\s*\\n([\\s\\S]*?)\\n\\s*\\[double dollar]"},
      {"BLOCK_PLACEHOLDER_PATTERN", "(?m)^[ \\t]*\\[double dollar][ \\t]*$"},
      {"INLINE_MATH_PATTERN", "(?<!\\\\)(?<!\\$)\\$([^$\\n]+?)(?<!\\\\)(?<!\\$)\\$"},
      {"SPORTS_SCORE_PATTERN", "^\\d{1,3}\\s*[:：]\\s*\\d{1,3}$"},
      {"TRIPLE_DOLLAR_CURRENCY_PATTERN", "(?<=^|\\s)\\$\\$\\$(?=\\d)"},
      {"SINGLE_CURRENCY_PATTERN", "(?<=^|\\s)(?<!\\\\)\\$(?=\\d)"},
      {"DOUBLE_CURRENCY_PATTERN", "(?<=^|\\s)\\$\\$(?=\\d)"},
      {"PURE_BLOCK_DOLLAR_MATH_REGEX", "^\\s*\\$\\$[\\s\\S]*\\$\\$\\s*$"},
      {"PURE_BLOCK_BRACKET_MATH_REGEX", "^\\s*\\\\\\[[\\s\\S]*\\\\\\]\\s*$"}
    };
    for (String[] p: ps) {
      try { Pattern.compile(p[1]); System.out.println("OK " + p[0]); }
      catch (Throwable t) { System.out.println("BAD " + p[0] + " => " + t); }
    }
  }
}
