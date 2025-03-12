package com.jeffbrower;

import static com.jeffbrower.Logger.log;
import static com.jeffbrower.Logger.stringify;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

public class Checker {
   public static boolean check(final Reader r, final FormatOptions o) throws IOException {
      final StringProgress progress = new StringProgress();

      try (final Parser p = new Parser(new CheckingReader(r, progress), new Formatter(new CheckingWriter(progress), o))) {
         p.parseAll();

         // if more text was written than was read (usually missing a newline at EOF), fail formatting
         return progress.string.isEmpty();
      } catch (final InputMismatchException e) {
         return false;
      }
   }

   private Checker() {
      throw new UnsupportedOperationException();
   }

   private static class InputMismatchException extends RuntimeException {
      // TODO perhaps investigate whether it would be easy to show a diff, or at least point to a location in the input?
   }

   private static enum Direction {
      WRITING, READING
   }

   /**
    * This class is for tracking and comparing the "read" vs "written" string data. Text data is
    * added with the {@link #add} method, in either the WRITING or READING direction. If there's
    * already excess data in the direction we're adding to, it will just add data to the queue. If
    * the direction we're adding is the opposite of what we already have data for, we are going to
    * validate that all the new data matches what we already have in the queue. For example, if we
    * have already read "<root />\n" (i.e. state is `direction=READING, string="<root />\n"`), and
    * we receive a request to write "<root", that fully matches what was already read, so we can
    * disregard all of the matching text, and the new state is `direction=READING, string=" />\n"`.
    */
   private static class StringProgress {
      private Direction direction;
      private String string = "";

      private synchronized void add(final Direction newDirection, final String newString) {
         log("  add");
         log("  - direction: " + direction);
         log("  - string: " + stringify(string));
         log("  - newDirection: " + newDirection);
         log("  - newString: " + stringify(newString));

         if (string.isEmpty() || direction == newDirection) {
            // if we're already headed a certain direction (or we have no progress), just append the string

            direction = newDirection;
            string += newString;
         } else if (newString.length() < string.length()) {
            // the new string will not fully consume our progress

            if (!string.startsWith(newString)) {
               throw new InputMismatchException();
            }

            string = string.substring(newString.length());
         } else {
            // the new string will fully consume our progress, so switch directions
   
            if (!newString.startsWith(string)) {
               throw new InputMismatchException();
            }
   
            direction = newDirection;
            string = newString.substring(string.length());
         }

         log("  - direction: " + direction);
         log("  - string: " + stringify(string));
      }
   }

   private static class CheckingReader extends Reader {
      private final Reader r;
      private final StringProgress progress;

      private CheckingReader(final Reader r, final StringProgress progress) {
         this.r = r;
         this.progress = progress;
      }

      @Override
      public int read(final char[] cbuf, final int off, final int len) throws IOException {
         final int count = r.read(cbuf, off, len);
         if (count != -1) {
            // if not EOF, append the read characters to the string
            progress.add(Direction.READING, new String(cbuf, off, count));
         }
         return count;
      }

      @Override
      public void close() throws IOException {
         r.close();
      }
   }

   private static class CheckingWriter extends Writer {
      private final StringProgress progress;

      private CheckingWriter(final StringProgress progress) {
         this.progress = progress;
      }

      @Override
      public void write(final char[] cbuf, final int off, final int len) throws IOException {
         // append the read characters to the string
         progress.add(Direction.WRITING, new String(cbuf, off, len));
      }

      @Override
      public void flush() throws IOException {
         // nothing to do
      }

      @Override
      public void close() throws IOException {
         // nothing to do
      }
   }
}
