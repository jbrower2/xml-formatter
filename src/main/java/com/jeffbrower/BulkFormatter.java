package com.jeffbrower;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;

public class BulkFormatter {
   public static void main(final String[] args) {
      for (final String arg : args) {
         final Path dir = Path.of(arg);
         if (!Files.isDirectory(dir)) {
            System.err.println("Not a directory: " + arg);
            continue;
         }
         traverse(dir);
      }
   }

   private static void traverse(final Path dir) {
      final List<Path> paths;
      try {
         paths = Files.list(dir).collect(Collectors.toList());
      } catch (final IOException e) {
         System.err.println("Error listing files in " + dir);
         e.printStackTrace();
         return;
      }

      for (final Path path : paths) {
         if (Files.isDirectory(path)) {
            traverse(path);
         } else if (Files.isRegularFile(path)) {
            final String lowerName = path.getFileName().toString().toLowerCase();
            if (lowerName.endsWith(".xml") || lowerName.endsWith(".xhtml")) {
               format(path);
            }
         }
      }
   }

   private static void format(final Path file) {
      try {
         final String baseName = file.getFileName().toString();
         final int lastDot = baseName.lastIndexOf('.');
         final Path tempFile = Files.createTempFile(baseName.substring(0, lastDot), baseName.substring(lastDot));

         final FormatOptions o = new FormatOptions();

         try (
            final Reader r = new FileReader(file.toFile(), StandardCharsets.UTF_8);
            final Writer w = new FileWriter(tempFile.toFile(), StandardCharsets.UTF_8)
         ) {
            Formatter.format(r, w, o);
         }

         Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
         System.out.println("Formatted file " + file);
      } catch (final Exception e) {
         System.err.println("Error formatting file " + file);
         e.printStackTrace();
      }
   }
}
