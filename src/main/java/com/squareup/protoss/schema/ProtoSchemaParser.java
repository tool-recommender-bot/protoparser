/*
 * Copyright (C) 2012 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.protoss.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Basic parser for {@code .proto} schema declarations.
 *
 * <p>This parser throws away data that it doesn't care about. In particular,
 * unrecognized options, and extensions are discarded. It doesn't retain nesting
 * within types.
 */
public final class ProtoSchemaParser {

  /** The entire document */
  private final char[] data;

  /** Our cursor within the document. {@code data[pos]} is the next character to be read. */
  private int pos;

  /** The number of newline characters encountered thus far. */
  private int line;

  /** The index of the most recent newline character. */
  private int lineStart;

  /** Output package name, or null if none yet encountered. */
  private String packageName;

  /** Imported files. */
  private List<String> dependencies = new ArrayList<String>();

  /** Declared messages, including nested messages. */
  private List<MessageType> messageTypes = new ArrayList<MessageType>();

  /** Declared messages, including nested enums. */
  private List<EnumType> enumTypes = new ArrayList<EnumType>();

  ProtoSchemaParser(String data) {
    this.data = data.toCharArray();
  }

  public ProtoFile readProtoFile(String fileName) {
    while (true) {
      String documentation = readDocumentation();
      if (pos == data.length) {
        return new ProtoFile(fileName, packageName, dependencies, messageTypes, enumTypes);
      }
      readDeclaration(documentation, false);
    }
  }

  private Object readDeclaration(String documentation, boolean nested) {
    String label = readWord();

    if (label.equals("message")) {
      readMessage(documentation);
      return null;

    } else if (label.equals("enum")) {
      readEnumType(documentation);
      return null;

    } else if (label.equals("package")) {
      if (nested) throw unexpected("nested package");
      if (packageName != null) throw unexpected("too many package names");
      packageName = readWord();
      if (readChar() != ';') throw unexpected("expected ';'");
      return null;

    } else if (label.equals("option")) {
      if (nested) throw unexpected("nested option");
      readWord(); // Option name.
      if (readChar() != '=') throw unexpected("expected '=' in option");
      readString(); // Option value.
      if (readChar() != ';') throw unexpected("expected ';'");
      return null;

    } else if (label.equals("required") || label.equals("optional") || label.equals("repeated")) {
      if (!nested) throw unexpected("fields must be nested");
      return readField(documentation, label);

    } else if (label.equals("extensions")) {
      if (!nested) throw unexpected("extensions must be nested");
      readWord(); // Range start.
      readWord(); // Literal 'to'
      readWord(); // Range end.
      if (readChar() != ';') throw unexpected("expected ';'");
      return null;

    } else if (label.equals("import")) {
      dependencies.add(readString());
      if (readChar() != ';') throw unexpected("expected ';'");
      return null;

    } else {
      throw unexpected("unexpected label: " + label);
    }
  }

  /**
   * Reads a message declaration.
   */
  private void readMessage(String documentation) {
    String name = readWord();
    List<MessageType.Field> fields = new ArrayList<MessageType.Field>();
    if (readChar() != '{') throw unexpected("expected '{'");
    while (true) {
      String nestedDocumentation = readDocumentation();
      if (peekChar() == '}') {
        pos++;
        break;
      }
      Object declared = readDeclaration(nestedDocumentation, true);
      if (declared instanceof MessageType.Field) {
        fields.add((MessageType.Field) declared);
      }
    }
    messageTypes.add(new MessageType(name, documentation, fields));
  }

  /**
   * Reads an enumerated type declaration and returns it.
   */
  private void readEnumType(String documentation) {
    String name = readWord();
    List<EnumType.Value> values = new ArrayList<EnumType.Value>();
    if (readChar() != '{') throw unexpected("expected '{'");
    while (true) {
      String valueDocumentation = readDocumentation();
      if (peekChar() == '}') {
        pos++;
        break;
      }
      values.add(readEnumValue(valueDocumentation));
    }
    enumTypes.add(new EnumType(name, documentation, values));
  }

  /**
   * Reads an field declaration and returns it.
   */
  private MessageType.Field readField(String documentation, String label) {
    boolean deprecated = false;
    String defaultValue = null;

    MessageType.Label labelEnum = MessageType.Label.valueOf(label.toUpperCase(Locale.US));
    String type = readWord();
    String name = readWord();
    if (readChar() != '=') throw unexpected("expected '='");
    int tag = readInt();
    char c = peekChar();
    if (c == '[') {
      Map<String, String> options = readOptions();
      String deprecatedString = options.get("deprecated");
      deprecated = deprecatedString != null ? Boolean.valueOf(deprecatedString) : false;
      defaultValue = options.get("default");
      c = peekChar();
    }
    if (c == ';') {
      pos++;
      return new MessageType.Field(
          labelEnum, type, name, tag, defaultValue, deprecated, documentation);
    }
    throw unexpected("expected ';'");
  }

  private Map<String, String> readOptions() {
    if (readChar() != '[') throw new AssertionError();
    Map<String, String> result = new LinkedHashMap<String, String>();
    // Handle '[]' as a special case. Otherwise we need state to avoids invalid cases like '[,]'.
    if (peekChar() == ']') {
      pos++;
      return result;
    }
    // Each iteration of this loop reads a value.
    while (true) {
      String optionName = readWord();
      if (readChar() != '=') throw unexpected("expected '='");
      String optionValue = readString();
      result.put(optionName, optionValue);

      char c = readChar();
      if (c == ']') {
        return result;
      } else if (c != ',') {
        throw unexpected("expected ','");
      }
    }
  }

  /**
   * Reads an enum constant and returns it.
   */
  private EnumType.Value readEnumValue(String documentation) {
    String name = readWord();
    if (readChar() != '=') throw unexpected("expected '='");
    int tag = readInt();
    if (readChar() != ';') throw unexpected("expected ';'");
    return new EnumType.Value(name, tag, documentation);
  }

  /**
   * Reads a non-whitespace character and returns it.
   */
  private char readChar() {
    char result = peekChar();
    pos++;
    return result;
  }

  /**
   * Peeks a non-whitespace character and returns it. The only difference
   * between this and {@code readChar} is that this doesn't consume the char.
   */
  private char peekChar() {
    skipWhitespace(true);
    if (pos == data.length) throw unexpected("unexpected end of file");
    return data[pos];
  }

  /**
   * Reads a quoted or unquoted string and returns it.
   */
  private String readString() {
    skipWhitespace(true);
    return peekChar() == '"' ? readQuotedString() : readWord();
  }

  private String readQuotedString() {
    if (readChar() != '"') throw new AssertionError();
    StringBuilder result = new StringBuilder();
    while (pos < data.length) {
      char c = data[pos++];
      if (c == '"') return result.toString();

      if (c == '\\') {
        if (pos == data.length) throw unexpected("unexpected end of file");
        c = data[pos++];
      }

      result.append(c);
      if (c == '\n') newline();
    }
    throw unexpected("unterminated string");
  }

  /**
   * Reads a non-empty word and returns it.
   */
  private String readWord() {
    skipWhitespace(true);
    int start = pos;
    while (pos < data.length) {
      char c = data[pos];
      if ((c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || (c == '_')
          || (c == '-')
          || (c == '.')) {
        pos++;
      } else {
        break;
      }
    }
    if (start == pos) throw unexpected("expected a word");
    return new String(data, start, pos - start);
  }

  /**
   * Reads an integer and returns it.
   */
  private int readInt() {
    String tag = readWord();
    try {
      return Integer.valueOf(tag);
    } catch (Exception e) {
      throw unexpected("expected an integer but was " + tag);
    }
  }

  /**
   * Like {@link #skipWhitespace}, but this returns a string containing all
   * comment text. By convention, comments before a declaration document that
   * declaration.
   */
  private String readDocumentation() {
    String result = null;
    while (true) {
      skipWhitespace(false);
      if (pos == data.length || data[pos] != '/') {
        return result != null ? result : "";
      }
      String comment = readComment();
      result = (result == null)
          ? comment
          : (result + "\n" + comment);
    }
  }

  /**
   * Reads a comment and returns its body.
   */
  private String readComment() {
    if (pos == data.length || data[pos] != '/') throw new AssertionError();
    pos++;
    int commentType = pos < data.length ? data[pos++] : -1;
    if (commentType == '*') {
      int start = pos;
      while (pos + 1 < data.length) {
        if (data[pos] == '*' && data[pos + 1] == '/') {
          pos += 2;
          return new String(data, start, pos - 2 - start);
        } else {
          pos++;
          if (data[pos] == '\n') newline();
        }
      }
      throw unexpected("unterminated comment");
    } else if (commentType == '/') {
      int start = pos;
      while (pos < data.length) {
        char c = data[pos++];
        if (c == '\n') {
          newline();
          break;
        }
      }
      return new String(data, start, pos - 1 - start);
    } else {
      throw unexpected("unexpected '/'");
    }
  }

  /**
   * Skips whitespace characters and optionally comments. When this returns,
   * either {@code pos == data.length} or a non-whitespace character.
   */
  private void skipWhitespace(boolean skipComments) {
    while (pos < data.length) {
      char c = data[pos];
      if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
        pos++;
        if (c == '\n') newline();
      } else if (skipComments && c == '/') {
        readComment();
      } else {
        break;
      }
    }
  }

  /**
   * Call this everytime a '\n' is encountered.
   */
  private void newline() {
    line++;
    lineStart = pos;
  }

  private int column() {
    return pos - lineStart + 1;
  }

  private int line() {
    return line + 1;
  }

  private RuntimeException unexpected(String message) {
    throw new IllegalStateException(
        String.format("Syntax error at at %d:%d: %s", line(), column(), message));
  }
}
