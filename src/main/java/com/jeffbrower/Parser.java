package com.jeffbrower;

import static com.jeffbrower.Logger.log;
import static com.jeffbrower.Logger.stringify;

import java.io.Closeable;
import java.io.IOException;
import java.io.PushbackReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

public class Parser implements Closeable {
   private final PushbackReader r;
   private final Formatter f;

   public Parser(final PushbackReader r, final Formatter f) {
      this.r = r;
      this.f = f;
   }

   @Override
   public void close() throws IOException {
      r.close();
   }

   // base parsing methods

   /** Parse the entire input and write the entire output. */
   public void parseAll() throws IOException {
      while (parseOneStep()) {
         // keep going
      }
   }

   /**
    * Parse one step, meaning consume one element or text node. Useful for incremental processing, like in a format-checking context.
    *
    * @return true if there is more to parse, false if we've reached the end of the file
    */
   public boolean parseOneStep() throws IOException {
      final int c = r.read();
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

      // the data with leading whitespace trimmed
      final String charDataTrimStart = rawCharData.stripLeading();
      if (charDataTrimStart.isEmpty()) {
         // string was entirely whitespace
         if (hasMultipleNewlines(rawCharData)) {
            // an entirely-whitespace string still produces a newline if it contained multiple newlines
            // this is how we can preserve blank lines between elements (though we only preserve 1, by design)
            f.writeNewLine();
         } else {
            log("empty char data: " + stringify(rawCharData));
         }
         // not EOF yet
         return true;
      }

      final String whitespacePrefix = rawCharData.substring(0, rawCharData.length() - charDataTrimStart.length());
      if (hasMultipleNewlines(whitespacePrefix)) {
         // if there were multiple newlines at the beginning of this text, add a blank line before the text
         f.writeNewLine();
      }

      // print the trimmed text. we don't alter the indentation or wrapping if the text itself was multiple lines
      final String charData = charDataTrimStart.stripTrailing();
      f.writeText(charData);

      final String whitespaceSuffix = charDataTrimStart.substring(charData.length());
      if (hasMultipleNewlines(whitespaceSuffix)) {
         // if there were multiple newlines at the end of this text, add a blank line after the text
         f.writeNewLine();
      }

      // not EOF yet
      return true;
   }

   private String parseCharData(final String end) throws IOException {
      // https://www.w3.org/TR/xml11/#NT-CharData
      // note: this implementation allows CharData to contain References

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

      return b.toString();
   }

   private static boolean hasMultipleNewlines(final String s) {
      final int i = s.indexOf('\n');
      return i != -1 && s.indexOf('\n', i + 1) != -1;
   }

   private String parseReference() throws IOException {
      // precondition: already read: "&"
      // https://www.w3.org/TR/xml11/#NT-Reference

      int c = r.read();

      if (c != '#') {
         // named reference, like &nbsp;

         // push the character back onto the Reader so it's part of 'name'
         r.unread(c);
         final String name = parseName();

         // must end with ';'
         c = r.read();
         if (c != ';') {
            throw new IllegalStateException("Expected end of reference: " + c);
         }

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
      if (c != ';') {
         throw new IllegalStateException("Expected end of reference: " + c);
      }

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
      // https://www.w3.org/TR/xml11/#NT-Name

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
      // https://www.w3.org/TR/xml11/#NT-S

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
         parseQuestionTag();
         return;
      }

      // comments, CDATA, and DOCTYPE tags
      if (c == '!') {
         parseExclamationTag();
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
      // EmptyElemTag - https://www.w3.org/TR/xml11/#NT-EmptyElemTag <xxx ... />
      // STag - https://www.w3.org/TR/xml11/#NT-STag <xxx ... >

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
            c = r.read();
            if (c != '>') {
               throw new IllegalStateException("Expected '>': " + c);
            }

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

         // there's optional whitespace before the '=' of an attribute
         parseWhitespace();

         c = r.read();
         if (c != '=') {
            throw new IllegalStateException("Expected '=': " + c);
         }

         // there's optional whitespace after the '=' of an attribute
         parseWhitespace();

         // XML attributes can be single or double quoted, we'll allow either, and preserve which one was used
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

         // construct the full attribute string, including the key, '=', quotes, and value
         attributes.add(key + '=' + quoteString + value + quoteString);
      }
   }

   private void parseEndTag() throws IOException {
      // precondition: already read: "</"
      // https://www.w3.org/TR/xml11/#NT-ETag

      final String tagName = parseName();

      // there's optional whitespace after the tag name in closing tags
      parseWhitespace();

      final int c = r.read();
      if (c != '>') {
         throw new IllegalStateException("Expected '>': " + c);
      }

      f.writeEndTag(tagName);
   }

   private void parseQuestionTag() throws IOException {
      // precondition: already read: "<?"

      // XMLDecl - https://www.w3.org/TR/xml11/#NT-XMLDecl - <?xml ... ?>
      // PI - https://www.w3.org/TR/xml11/#NT-PI - <? ... ?>

      throw new UnsupportedOperationException("TODO xml declarations/PI tags are not implemented");
   }

   private void parseExclamationTag() throws IOException {
      // precondition: already read: "<!"

      // doctypedecl - https://www.w3.org/TR/xml11/#NT-doctypedecl - <!DOCTYPE ... >
      // CDSect - https://www.w3.org/TR/xml11/#NT-CDSect - <![CDATA[ ... ]]>
      // Comment - https://www.w3.org/TR/xml11/#NT-Comment <!-- ... -->

      throw new UnsupportedOperationException("TODO comments/cdada/doctype tags are not implemented");
   }
}
