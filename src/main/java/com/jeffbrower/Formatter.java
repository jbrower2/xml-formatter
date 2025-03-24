package com.jeffbrower;

import static com.jeffbrower.Logger.log;
import static com.jeffbrower.Logger.stringify;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.List;

/** @see {@link #format} */
public class Formatter implements Closeable {
   /**
    * Formats a file's contents according to the specified format.
    *
    * @param r The {@link Reader} that provides the file's contents.
    * @param w The {@link Writer} to write the formatted file to.
    * @param o The {@link FormatOptions} to configure how the file is formatted.
    */
   public static void format(final Reader r, final Writer w, final FormatOptions o) throws IOException {
      try (final Parser p = new Parser(r, new Formatter(w, o))) {
         p.parseAll();
      }
   }

   private final Writer w;
   private final FormatOptions o;

   Formatter(final Writer w, final FormatOptions o) {
      this.w = w;
      this.o = o;
   }

   @Override
   public void close() throws IOException {
      w.close();
   }

   private static enum OneLineStatus {
      /** We know this element can fit on one line, using spaces between attributes. */
      ONE_LINE,

      /** This element can fit on one line if there are any child elements. If it is empty, we need 2 extra characters to add " /" before the ">". */
      ONE_LINE_UNLESS_EMPTY,

      /** We know this element cannot fit on one line, so put all attributes on their own lines. */
      MULTIPLE_LINES
   }

   // formatting state variables

   /** Current indentation level. */
   private int indent = 0;

   /**
    * If null: a start tag is not in progress
    * Non-null: the in-progress start tag's 'oneLine' status
    */
   private OneLineStatus startTagOneLine = null;

   /**
    * Attributes of the start tag, if startTagOneLine=ONE_LINE_UNLESS_EMPTY. We can't write them until we know what the format is.
    */
   private List<String> inProgressAttributes = null;

   /**
    * If null: a tag is not in progress (or the in-progress tag is not one-line)
    * Non-null: the in-progress tag's maximum length of a child string for it to still fit on one line
    */
   private Integer maxChildStringOneLineLength = null;

   /**
    * (see maxChildStringOneLineLength)
    * The string that will be used as the only child of this element, if it is the only child
    */
   private String childStringOneLine = null;

   private void writeIndent() throws IOException {
      w.write(o.useTabs ? "\t".repeat(indent) : " ".repeat(indent * o.tabWidth));
   }

   void writeEmptyTag(final String tagName, final List<String> attributes) throws IOException {
      log("writeEmptyTag");
      log("- tagName: " + tagName);

      // if we have a partial start tag, finish it and then add this as a child
      finishInProgressStuff();

      final boolean oneLine;
      if (o.singleAttributePerLine) {
         // allow overriding this behavior to always have one attribute per line
         oneLine = false;
      } else {
         // indent (important! we still use tabWidth even if you use tabs) + "<tagName".length
         int lineLength = indent * o.tabWidth + 1 + tagName.length();

         // add length of all attributes
         for (final String attribute : attributes) {
            lineLength += 1 + attribute.length();
         }

         // add " />".length
         lineLength += 3;

         log("- lineLength: " + lineLength);

         // if the total length of the line fits in printWidth, keep it on one line
         oneLine = lineLength <= o.printWidth;
      }
      log("- oneLine: " + oneLine);

      writeTagStart(tagName, attributes, oneLine ? OneLineStatus.ONE_LINE : OneLineStatus.MULTIPLE_LINES);

      // allow the bracket to be on the same line even in multi-line tags, using bracketSameLine
      w.write(oneLine || o.bracketSameLine ? " />" : "/>");
      w.write(o.endOfLine.string);
   }

   void writeStartTag(final String tagName, final List<String> attributes) throws IOException {
      log("writeStartTag");
      log("- tagName: " + tagName);

      // if we have a partial start tag, finish it and then add this as a child
      finishInProgressStuff();

      // indent (important! we still use tabWidth even if you use tabs) + "<tagName".length
      int lineLength = indent * o.tabWidth + 1 + tagName.length();

      // add length of all attributes
      for (final String attribute : attributes) {
         lineLength += 1 + attribute.length();
      }
      log("- lineLength: " + lineLength);

      final OneLineStatus oneLine;
      if (attributes.isEmpty() || lineLength + 3 <= o.printWidth) {
         // ONE_LINE = guaranteed to fit on one line
         // always use one line if there are zero attributes, even if the tag name is longer than the printWidth
         // we use 'lineLength + 3' to account for the " />" if this tag ends up being empty
         oneLine = OneLineStatus.ONE_LINE;
      } else if (o.singleAttributePerLine || lineLength + 1 > o.printWidth) {
         // MULTIPLE_LINES = guaranteed NOT to fit on one line, so split each attribute to its own line
         // allow manually overriding to always use multiple lines with singleAttributePerLine
         // we use 'lineLength + 1' to account for the ">" at the end of a normal start tag
         oneLine = OneLineStatus.MULTIPLE_LINES;
      } else {
         // this case is for line lengths between the two above cases, so if the tag is empty and
         // we have to add " />", the line will be longer than printWidth. but if we have a normal
         // start tag that ends with ">", it WILL fit on one line. in this case, we skip writing
         // attributes until after we know whether it's empty
         oneLine = OneLineStatus.ONE_LINE_UNLESS_EMPTY;
      }
      log("- oneLine: " + oneLine);

      writeTagStart(tagName, attributes, oneLine);

      startTagOneLine = oneLine;

      // calculate the max length of a string so that the whole tag will fit in one line, like:
      // <tagName>Some Text</tagName>
      if (oneLine == OneLineStatus.ONE_LINE) {
         // add the closing ">", plus "</tagName>".length
         lineLength += 1 + 2 + tagName.length() + 1;

         maxChildStringOneLineLength = o.printWidth - lineLength;

         // if the max string length is less than 1 character, don't allow it
         if (maxChildStringOneLineLength <= 0) {
            maxChildStringOneLineLength = null;
         }
      }
   }

   private void writeTagStart(final String tagName, final List<String> attributes, final OneLineStatus oneLine) throws IOException {
      // precondition: currently on a new blank line, and there is no in-progress start tag (which is the postcondition of finishInProgressStuff)
      // postcondition: the full state of a start/empty tag is either 1. printed to the Writer, or 2. stored in the state variables of this class

      log("  writeTagStart");
      log("  - tagName: " + tagName);
      log("  - oneLine: " + oneLine);

      // indent the start tag
      writeIndent();

      // write "<tagName"
      w.write('<');
      w.write(tagName);

      // short-circuit in the event that there are no attributes
      if (attributes.isEmpty()) {
         log("  - empty attributes");
         return;
      }

      switch (oneLine) {
         case ONE_LINE:
            writeAttributes(attributes, true);
            break;
         case MULTIPLE_LINES:
            writeAttributes(attributes, false);
            break;
         case ONE_LINE_UNLESS_EMPTY:
            // don't write the attributes now, save them until we know whether the tag is one-line or not
            inProgressAttributes = attributes;
            break;
         default:
            throw new IllegalArgumentException("Unexpected OneLineStatus: " + oneLine);
      }
   }

   private void writeAttributes(final List<String> attributes, final boolean oneLine) throws IOException {
      log("    writeAttributes");
      log("    - oneLine: " + oneLine);

      indent++;
      log("    - indent++: " + indent);

      log("    - attributes:");
      for (final String attribute : attributes) {
         log("      - " + attribute);

         if (oneLine) {
            w.write(' ');
         } else {
            w.write(o.endOfLine.string);
            writeIndent();
         }

         w.write(attribute);
      }

      indent--;
      log("    - indent--: " + indent);

      // allow the bracket to be on the same line even in multi-line tags, using bracketSameLine
      if (!(oneLine || o.bracketSameLine)) {
         w.write(o.endOfLine.string);
         writeIndent();
      }
   }

   /** If a start tag is partially-complete, this method finishes that start tag so that child elements may be added after. */
   private void finishInProgressStuff() throws IOException {
      finishInProgressStuff(null);
   }

   private void finishInProgressStuff(final String endTag) throws IOException {
      // postcondition: currently on a new blank line, and there is no in-progress start tag

      log("  finishInProgressStuff");
      log("  - endTag: " + endTag);

      if (startTagOneLine == null) {
         // this is the case where a start tag is NOT in progress
         // because of that, we know we are already on a new blank line

         log("  - no text to consider");
         if (endTag != null) {
            // if we're adding an end tag, write that now

            log("  - writing end tag");

            // now that we're closing that tag, decrease the indent level
            indent--;
            log("  - indent--: " + indent);
            writeIndent();

            // close the tag
            w.write("</");
            w.write(endTag);
            w.write('>');

            // end on a new line
            w.write(o.endOfLine.string);
         }

         return;
      }

      // the rest of this method is for dealing with an in-progress start tag

      final boolean emptyTag = childStringOneLine == null && endTag != null;
      log("  - emptyTag: " + emptyTag);

      final boolean oneLine;
      switch (startTagOneLine) {
         case ONE_LINE:
            oneLine = true;
            break;
         case MULTIPLE_LINES:
            oneLine = false;
            break;
         case ONE_LINE_UNLESS_EMPTY:
            oneLine = !emptyTag;

            // now we know whether the tag is empty or not, so we can write the in-progress attributes
            // see writeStartTag for a deeper explanation
            writeAttributes(inProgressAttributes, oneLine);

            break;
         default:
            throw new IllegalArgumentException("Unexpected OneLineStatus: " + startTagOneLine);
      }
      log("  - oneLine: " + oneLine);

      // if this is NOT an empty tag, add ">"
      // if this is a one-line empty tag, add " />"
      // if this is a multi-line empty tag, add "/>"
      w.write(!emptyTag ? ">" : oneLine ? " />" : "/>");

      // increase indent now that the tag is closed, and use that for all children
      if (endTag == null) {
         indent++;
         log("  - indent++: " + indent);
      }

      startTagOneLine = null;
      maxChildStringOneLineLength = null;

      if (childStringOneLine != null) {
         // deal with the stored text

         if (endTag == null) {
            // tag is not ending, so the stored text will be one of several children
            // so we write it on a new, indented line
            w.write(o.endOfLine.string);
            writeIndent();
         }

         log("  - writing text: " + childStringOneLine);
         w.write(childStringOneLine);
         childStringOneLine = null;

         if (endTag != null) {
            // if we're adding an end tag, write it here
            log("  - writing end tag");
            w.write("</");
            w.write(endTag);
            w.write('>');
         }
      }

      // end on a new line
      w.write(o.endOfLine.string);
   }

   void writeEndTag(final String tagName) throws IOException {
      log("writeEndTag");
      log("- tagName: " + tagName);

      finishInProgressStuff(tagName);
   }

   void writeNewLine() throws IOException {
      log("writeNewLine");

      // if we have a partial start tag, finish it and then add this as a child
      finishInProgressStuff();

      // add the blank line
      w.write(o.endOfLine.string);
   }

   void writeText(final String text) throws IOException {
      log("writeText");
      log("- text: " + stringify(text));

      // if the in-progress start tag is one-line and this text could fit and still be a single line, store it for later
      if (
         maxChildStringOneLineLength != null &&
         text.length() <= maxChildStringOneLineLength &&
         !text.contains("\n")
      ) {
         log("- saving text for later");

         // make sure to set maxChildStringOneLineLength to null so that we don't end up with multiple strings
         maxChildStringOneLineLength = null;
         childStringOneLine = text;
         return;
      }

      // if we have a partial start tag, finish it and then add this as a child
      finishInProgressStuff();

      log("- writing text");
      writeIndent();
      w.write(text);

      // end on a new line
      w.write(o.endOfLine.string);
   }

   void writeProcessingInstruction(final String contents) throws IOException {
      log("writeProcessingInstruction");
      log("- contents: " + stringify(contents));

      // if we have a partial start tag, finish it and then add this as a child
      finishInProgressStuff();

      writeIndent();
      w.write("<?");
      w.write(contents);
      w.write("?>");

      // end on a new line
      w.write(o.endOfLine.string);
   }

   void writeDoctype(final String tag) throws IOException {
      log("writeDoctype");
      log("- tag: " + stringify(tag));

      // if we have a partial start tag, finish it and then add this as a child
      finishInProgressStuff();

      writeIndent();
      w.write(tag);

      // end on a new line
      w.write(o.endOfLine.string);
   }

   void writeCdata(final String contents) throws IOException {
      log("writeCdata");
      log("- contents: " + stringify(contents));

      // if we have a partial start tag, finish it and then add this as a child
      finishInProgressStuff();

      writeIndent();
      w.write("<![CDATA[");
      w.write(contents);
      w.write("]]>");

      // end on a new line
      w.write(o.endOfLine.string);
   }

   void writeComment(final String contents) throws IOException {
      log("writeComment");
      log("- contents: " + stringify(contents));

      // if we have a partial start tag, finish it and then add this as a child
      finishInProgressStuff();

      writeIndent();
      w.write("<!--");
      w.write(contents);
      w.write("-->");

      // end on a new line
      w.write(o.endOfLine.string);
   }

   void printDebugInfo() {
      log("- indent: " + indent);
      log("- startTagOneLine: " + startTagOneLine);
      log("- inProgressAttributes:" + inProgressAttributes);
      log("- maxChildStringOneLineLength: " + maxChildStringOneLineLength);
      log("- childStringOneLine: " + childStringOneLine);
   }
}
