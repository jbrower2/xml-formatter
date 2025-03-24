package com.jeffbrower;

import static com.jeffbrower.Logger.log;
import static com.jeffbrower.Logger.stringify;

import java.io.Closeable;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

class Parser implements Closeable {
   private static Pattern VERSION = Pattern.compile("(['\"])1\\.\\d+\\1");
   private static Pattern ENCODING = Pattern.compile("(['\"])UTF-8\\1", Pattern.CASE_INSENSITIVE);
   private static Pattern STANDALONE = Pattern.compile("(['\"])(?:yes|no)\\1");

   private final PushbackReader r;
   private final Formatter f;

   Parser(final Reader r, final Formatter f) {
      this.r = r instanceof PushbackReader ? (PushbackReader) r : new PushbackReader(r);
      this.f = f;
   }

   @Override
   public void close() throws IOException {
      try (r; f) {
         // close everything
      }
   }

   // base parsing methods

   /** Parse the entire input and write the entire output. */
   void parseAll() throws IOException {
      while (parseOneStep()) {
         // keep going
      }
   }

   /**
    * Parse one step, meaning consume one element or text node. Useful for incremental processing, like in a format-checking context.
    *
    * @return true if there is more to parse, false if we've reached the end of the file
    */
   boolean parseOneStep() throws IOException {
      int c = r.read();
      if (c == -1) {
         // reached EOF
         return false;
      }

      if (c == '<') {
         parseTag();
         return true;
      }

      // if the character wasn't a '<', push it back onto the Reader
      r.unread(c);

      // the raw character data, without any trimming
      final String rawCharData = parseCharData(null);

      // detect whether we're at EOF
      c = r.read();
      final boolean atEOF;
      if (c == -1) {
         atEOF = true;
      } else {
         atEOF = false;
         r.unread(c);
      }

      // the data with leading whitespace trimmed
      final String charDataTrimStart = rawCharData.stripLeading();
      if (charDataTrimStart.isEmpty()) {
         // string was entirely whitespace
         if (hasMultipleNewlines(rawCharData) && !atEOF) {
            // an entirely-whitespace string still produces a newline if it contained multiple newlines
            // this is how we can preserve blank lines between elements (though we only preserve 1, by design).
            // if this blank string is at the EOF, don't add an additional newline
            f.writeBlankLine();
         } else {
            log("empty char data: " + stringify(rawCharData));
         }
         // not EOF yet
         return true;
      }

      final String whitespacePrefix = rawCharData.substring(0, rawCharData.length() - charDataTrimStart.length());
      if (hasMultipleNewlines(whitespacePrefix)) {
         // if there were multiple newlines at the beginning of this text, add a blank line before the text
         f.writeBlankLine();
      }

      // print the trimmed text. we don't alter the indentation or wrapping if the text itself was multiple lines
      final String charData = charDataTrimStart.stripTrailing();
      f.writeText(charData);

      final String whitespaceSuffix = charDataTrimStart.substring(charData.length());
      if (hasMultipleNewlines(whitespaceSuffix)) {
         // if there were multiple newlines at the end of this text, add a blank line after the text
         f.writeBlankLine();
      }

      // not EOF yet
      return true;
   }

   private String parseCharData(final String end) throws IOException {
      // note: this implementation allows CharData to contain References
      // 1.0: https://www.w3.org/TR/xml/#NT-CharData
      // 1.1: https://www.w3.org/TR/xml11/#NT-CharData

      boolean ended = false;
      final StringBuilder b = new StringBuilder();
      while (true) {
         // allow this method to stop when it reaches a certain pattern, like ending an attribute when you encounter the closing ' or "
         if (
            end != null &&
            b.length() >= end.length() &&
            b.substring(b.length() - end.length()).equals(end)
         ) {
            // trim the 'end' off the string, since it was known ahead of time, and only return the inside of the CharData
            b.setLength(b.length() - end.length());
            ended = true;
            break;
         }

         final int c = r.read();
         if (c == -1) {
            // EOF, exit
            break;
         }

         if (c == '<') {
            // push the '<' back onto the Reader, and end this CharData
            r.unread('<');
            break;
         }

         if (c == '&') {
            // parse the reference
            b.append(parseReference());
            continue;
         }

         // append the character if it was not a reserved character
         b.append((char) c);
      }

      if (end != null && !ended) {
         throw new IllegalStateException("Expected '" + end + "' but string ended otherwise");
      }

      return b.toString();
   }

   private static boolean hasMultipleNewlines(final String s) {
      final int i = s.indexOf('\n');
      return i != -1 && s.indexOf('\n', i + 1) != -1;
   }

   private String parseReference() throws IOException {
      // precondition: already read: "&"
      // 1.0: https://www.w3.org/TR/xml/#NT-Reference
      // 1.1: https://www.w3.org/TR/xml11/#NT-Reference

      int c = r.read();

      if (c != '#') {
         // named reference, like &nbsp;

         // push the character back onto the Reader so it's part of 'name'
         r.unread(c);
         final String name = parseName();

         // must end with ';'
         assertCharacter(';');

         return "&" + name + ";";
      }

      // precondition: already read "&#"
      final StringBuilder b = new StringBuilder("&#");
      final IntPredicate digitTest;

      c = r.read();
      if (c == 'x') {
         // if the reference starts with "&#x", use hex digits
         b.append('x');
         digitTest = Parser::isHexDigit;
         c = r.read();
      } else {
         // otherwise, normal decimal digits
         r.unread(c);
         digitTest = Parser::isDigit;
      }

      boolean anyDigits = false;
      while (digitTest.test(c)) {
         anyDigits = true;
         b.append(c);
         c = r.read();
      }

      // must have at least 1 digit
      if (!anyDigits) {
         throw new IllegalStateException("Expected digits: " + c);
      }

      // must end with ';', and we can re-use the last read character since it failed 'digitTest'
      assertCharacter(c, ';');

      return b.append(';').toString();
   }

   /** @return true if the character is a decimal digit */
   private static boolean isDigit(final int c) {
      return c >= '0' && c <= '9';
   }

   /** @return true if the character is a hexadecimal digit */
   private static boolean isHexDigit(final int c) {
      return isDigit(c) || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
   }

   // main parsing methods

   private String parseName() throws IOException {
      // 1.0: https://www.w3.org/TR/xml/#NT-Name
      // 1.1: https://www.w3.org/TR/xml11/#NT-Name

      final StringBuilder b = new StringBuilder();

      int c = r.read();

      // require at least 1 character, and must start with a "name start character"
      if (!isNameStartChar(c)) {
         throw new IllegalStateException("Expected name start character: " + c);
      }

      // rest of characters must be "name characters"
      do {
         b.append((char) c);
         c = r.read();
      } while (isNameChar(c));

      // push last non-name-character back onto Reader (unless we're at EOF)
      if (c != -1) {
         r.unread(c);
      }

      return b.toString();
   }

   /** @return true if the character is a valid XML1.1 name start character */
   private static boolean isNameStartChar(final int c) {
      return c == ':' ||
             c >= 'A' && c <= 'Z' ||
             c == '_' ||
             c >= 'a' && c <= 'z' ||
             c >= 0xC0 && c <= 0xD6 ||
             c >= 0xD8 && c <= 0xF6 ||
             c >= 0xF8 && c <= 0x2FF ||
             c >= 0x370 && c <= 0x37D ||
             c >= 0x37F && c <= 0x1FFF ||
             c >= 0x200C && c <= 0x200D ||
             c >= 0x2070 && c <= 0x218F ||
             c >= 0x2C00 && c <= 0x2FEF ||
             c >= 0x3001 && c <= 0xDFFF || // assume all surrogates are for code points in [0x10000,0xEFFFF]
             c >= 0xF900 && c <= 0xFDCF ||
             c >= 0xFDF0 && c <= 0xFFFD;
   }

   /** @return true if the character is a valid XML1.1 name character */
   private static boolean isNameChar(final int c) {
      return isNameStartChar(c) ||
             c == '-' ||
             c == '.' ||
             isDigit(c) ||
             c == 0xB7 ||
             c >= 0x0300 && c <= 0x036F ||
             c >= 0x203F && c <= 0x2040;
   }

   /** The return value of this method contains the parsed whitespace, but we never actually care about it in most cases. */
   private String parseWhitespace() throws IOException {
      // 1.0: https://www.w3.org/TR/xml/#NT-S
      // 1.1: https://www.w3.org/TR/xml11/#NT-S

      final StringBuilder b = new StringBuilder();

      int c;
      while (isWhitespace(c = r.read())) {
         b.append((char) c);
      }

      // push the non-whitespace character back onto the Reader (unless we're at EOF)
      if (c != -1) {
         r.unread(c);
      }

      return b.length() == 0 ? null : b.toString();
   }

   /** @return true if the character is a valid XML1.1 whitespace character */
   private static boolean isWhitespace(final int c) {
      switch (c) {
         case 0x20:
         case 0x9:
         case 0xD:
         case 0xA:
            return true;
         default:
            return false;
      }
   }

   private void parseTag() throws IOException {
      // precondition: already read: "<"

      final int c = r.read();
      if (c == -1) {
         throw new IllegalStateException("Unclosed tag");
      }

      // xml declaraions and PI tags
      if (c == '?') {
         parseQuestion();
         return;
      }

      // comments, CDATA, and DOCTYPE tags
      if (c == '!') {
         parseExclamation();
         return;
      }

      // end tags
      if (c == '/') {
         parseEndTag();
         return;
      }

      // push the non-special character back onto the Reader and parse it as a standard tag
      r.unread(c);

      parseStartTag();
   }

   private void parseStartTag() throws IOException {
      // precondition: already read: "<"
      // empty tag 1.0: https://www.w3.org/TR/xml/#NT-EmptyElemTag
      // empty tag 1.1: https://www.w3.org/TR/xml11/#NT-EmptyElemTag
      // start tag 1.0: https://www.w3.org/TR/xml/#NT-STag
      // start tag 1.1: https://www.w3.org/TR/xml11/#NT-STag

      // get tag name
      final String tagName = parseName();

      // store attributes as strings, like 'key="value"'
      final List<String> attributes = new ArrayList<>();

      int c;
      while (true) {
         // there is mandatory whitespace before the first attribute and between attributes
         // though, in our parser, it won't be strictly required between attributes
         parseWhitespace();

         c = r.read();

         if (c == '/') {
            // empty tag
            assertCharacter('>');

            f.writeEmptyTag(tagName, attributes);
            return;
         }

         if (c == '>') {
            // normal start tag
            f.writeStartTag(tagName, attributes);
            return;
         }

         // not a special character, so push it back onto the Reader
         r.unread(c);

         // parse the attribute name
         final String key = parseName();

         // there's optional whitespace around the '=' of an attribute
         parseWhitespace();
         assertCharacter('=');
         parseWhitespace();

         // get the value including quotes
         final String quotedValue = parseString();

         // construct the full attribute string, including the key, '=', quotes, and value
         attributes.add(key + '=' + quotedValue);
      }
   }

   private String parseString() throws IOException {
      // XML attributes can be single or double quoted, we'll allow either, and preserve which one was used.
      // in order to fully support prettier's style of quoting strings, we'd need to parse the xml entity
      // references (&apos;, etc.) in strings, and replace them with their normal characters, but that adds
      // a non-trivial amount of complexity to this code, so we're going to skip it and call it "good enough"
      final int quote = r.read();
      if (quote != '"' && quote != '\'') {
         throw new IllegalStateException("Expected '\"' or '\\'': " + quote);
      }
      final String quoteString = String.valueOf((char) quote);

      // this isn't technically CharData in the spec, but it should work in all but the most obscure edge cases
      final String value = parseCharData(quoteString);

      return quoteString + value + quoteString;
   }

   private String parseRawString() throws IOException {
      final int quote = r.read();
      if (quote != '"' && quote != '\'') {
         throw new IllegalStateException("Expected '\"' or '\\'': " + quote);
      }

      final StringBuilder b = new StringBuilder();
      b.append((char) quote);

      int c;
      do {
         c = r.read();

         if (c == -1) {
            throw new IllegalStateException("Unclosed string");
         }

         b.append((char) c);
      } while (c != quote);

      return b.toString();
   }

   private void parseEndTag() throws IOException {
      // precondition: already read: "</"
      // 1.0: https://www.w3.org/TR/xml/#NT-ETag
      // 1.1: https://www.w3.org/TR/xml11/#NT-ETag

      final String tagName = parseName();

      // there's optional whitespace after the tag name in closing tags
      parseWhitespace();

      assertCharacter('>');

      f.writeEndTag(tagName);
   }

   private void parseQuestion() throws IOException {
      // precondition: already read: "<?"

      final String target = parseName();
      if ("xml".equals(target)) {
         parseXmlDeclaration();
      } else if ("xml".equalsIgnoreCase(target)) {
         throw new IllegalStateException("Processing Instruction cannot be case-insensitive \"xml\": " + target);
      } else {
         parseProcessingInstruction(target);
      }
   }

   private void parseXmlDeclaration() throws IOException {
      // precondition: already read: "<?xml"
      // 1.0: https://www.w3.org/TR/xml/#NT-XMLDecl
      // 1.1: https://www.w3.org/TR/xml11/#NT-XMLDecl

      final StringBuilder b = new StringBuilder("xml");

      // required space before version info
      parseWhitespace();

      // XML version is required
      assertCharacter('v');
      assertCharacter('e');
      assertCharacter('r');
      assertCharacter('s');
      assertCharacter('i');
      assertCharacter('o');
      assertCharacter('n');

      // optional whitespace around '='
      parseWhitespace();
      assertCharacter('=');
      parseWhitespace();

      final String version = parseString();
      if (!VERSION.matcher(version).matches()) {
         throw new IllegalStateException("Unexpected version: " + version);
      }
      b.append(" version=");
      b.append(version);

      parseWhitespace();

      int c = r.read();

      if (c == 'e') {
         assertCharacter('n');
         assertCharacter('c');
         assertCharacter('o');
         assertCharacter('d');
         assertCharacter('i');
         assertCharacter('n');
         assertCharacter('g');

         // optional whitespace around '='
         parseWhitespace();
         assertCharacter('=');
         parseWhitespace();

         final String encoding = parseString();
         if (!ENCODING.matcher(encoding).matches()) {
            throw new IllegalStateException("Only UTF-8 is supported: " + encoding);
         }
         b.append(" encoding=");
         b.append(encoding);

         parseWhitespace();
         c = r.read();
      }

      if (c == 's') {
         assertCharacter('t');
         assertCharacter('a');
         assertCharacter('n');
         assertCharacter('d');
         assertCharacter('a');
         assertCharacter('l');
         assertCharacter('o');
         assertCharacter('n');
         assertCharacter('e');

         // optional whitespace around '='
         parseWhitespace();
         assertCharacter('=');
         parseWhitespace();

         final String standalone = parseString();
         if (!STANDALONE.matcher(standalone).matches()) {
            throw new IllegalStateException("Only yes/no are allowed: " + standalone);
         }
         b.append(" standalone=");
         b.append(standalone);

         parseWhitespace();
         c = r.read();
      }

      assertCharacter(c, '?');
      assertCharacter('>');

      f.writeProcessingInstruction(b.toString());
   }

   private void parseProcessingInstruction(final String target) throws IOException {
      // precondition: already read: "<?{target}"
      // 1.0: https://www.w3.org/TR/xml/#NT-PI
      // 1.1: https://www.w3.org/TR/xml11/#NT-PI

      final StringBuilder b = new StringBuilder(target);

      // parse processing instruction contents
      boolean lastQuestion = false;
      while (true) {
         final int c = r.read();

         if (c == '?') {
            // keep track of consecutive ']'s
            lastQuestion = true;
         } else {
            if (lastQuestion && c == '>') {
               // found "?>", end the processing instruction
               b.setLength(b.length() - 1);
               break;
            }

            lastQuestion = false;
         }

         b.append((char) c);
      }

      f.writeProcessingInstruction(b.toString());
   }

   private void parseExclamation() throws IOException {
      // precondition: already read: "<!"

      final int c = r.read();
      switch (c) {
         case 'D':
            assertCharacter('O');
            assertCharacter('C');
            assertCharacter('T');
            assertCharacter('Y');
            assertCharacter('P');
            assertCharacter('E');
            parseDoctype();
            break;
            case '[':
            assertCharacter('C');
            assertCharacter('D');
            assertCharacter('A');
            assertCharacter('T');
            assertCharacter('A');
            assertCharacter('[');
            parseCdata();
            break;
         case '-':
            assertCharacter('-');
            parseComment();
            break;
         default:
            throw new IllegalStateException("Unexpected exclamation tag: " + c);
      }
   }

   private void parseDoctype() throws IOException {
      // precondition: already read: "<!DOCTYPE"
      // 1.0: https://www.w3.org/TR/xml/#NT-doctypedecl
      // 1.1: https://www.w3.org/TR/xml11/#NT-doctypedecl

      final StringBuilder b = new StringBuilder("<!DOCTYPE ");

      parseWhitespace();

      final String name = parseName();
      b.append(name);

      parseWhitespace();

      int c = r.read();
      if (c == 'S') {
         assertCharacter('Y');
         assertCharacter('S');
         assertCharacter('T');
         assertCharacter('E');
         assertCharacter('M');
         b.append(" SYSTEM ");

         parseWhitespace();

         final String system = parseRawString();
         b.append(system);

         parseWhitespace();

         c = r.read();
      } else if (c == 'P') {
         assertCharacter('U');
         assertCharacter('B');
         assertCharacter('L');
         assertCharacter('I');
         assertCharacter('C');
         b.append(" PUBLIC ");

         parseWhitespace();

         final String pubid = parseRawString();
         b.append(pubid);
         b.append(' ');

         parseWhitespace();

         final String system = parseRawString();
         b.append(system);

         parseWhitespace();

         c = r.read();
      }

      if (c == '[') {
         throw new UnsupportedOperationException("Internal DTD is not implemented");
      }

      assertCharacter(c, '>');
      b.append('>');

      f.writeDoctype(b.toString());
   }

   private void parseCdata() throws IOException {
      // precondition: already read: "<![CDATA["
      // 1.0: https://www.w3.org/TR/xml/#NT-CDSect
      // 1.1: https://www.w3.org/TR/xml11/#NT-CDSect

      final StringBuilder b = new StringBuilder();

      // parse cdata contents
      int bracketCount = 0;
      while (true) {
         final int c = r.read();

         if (c == ']') {
            // keep track of consecutive ']'s
            bracketCount++;
         } else {
            if (bracketCount == 2 && c == '>') {
               // found "]]>", end the cdata
               b.setLength(b.length() - 2);
               break;
            }

            bracketCount = 0;
         }

         b.append((char) c);
      }

      f.writeCdata(b.toString());
   }

   private void parseComment() throws IOException {
      // precondition: already read: "<!--"
      // 1.0: https://www.w3.org/TR/xml/#NT-Comment
      // 1.1: https://www.w3.org/TR/xml11/#NT-Comment

      final StringBuilder b = new StringBuilder();

      // parse comment contents
      int dashCount = 0;
      while (true) {
         final int c = r.read();

         if (c == '-') {
            // keep track of consecutive '-'s
            dashCount++;
         } else {
            if (dashCount == 2) {
               if (c != '>') {
                  // the xml spec doesn't allow "--" in comments
                  throw new IllegalStateException("'--' is not permitted in comments");
               }
   
               // found "-->", end the comment
               b.setLength(b.length() - 2);
               break;
            }

            dashCount = 0;
         }

         b.append((char) c);
      }

      f.writeComment(b.toString());
   }

   private void assertCharacter(final char expected) throws IOException {
      assertCharacter(r.read(), expected);
   }

   private void assertCharacter(final int actual, final char expected) {
      if (actual != expected) {
         throw new IllegalStateException("Expected '" + expected + "': " + actual);
      }
   }
}
