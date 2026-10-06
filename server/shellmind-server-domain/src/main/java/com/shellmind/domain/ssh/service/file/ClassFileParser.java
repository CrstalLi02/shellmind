package com.shellmind.domain.ssh.service.file;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * .class bytecode structure parser (zero dependencies, javap-style output).
 * <p>
 * Parses the ClassFile structure: magic/version, constant pool, class signature (extends/implements), fields, methods, class attributes,
 * and renders a read-only text view suitable for Monaco Java highlighting.
 */
public final class ClassFileParser {

    private static final int MAGIC = 0xCAFEBABE;

    // constant pool tags
    private static final int TAG_UTF8 = 1;
    private static final int TAG_INTEGER = 3;
    private static final int TAG_FLOAT = 4;
    private static final int TAG_LONG = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_CLASS = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_FIELDREF = 9;
    private static final int TAG_METHODREF = 10;
    private static final int TAG_INTERFACE_METHODREF = 11;
    private static final int TAG_NAME_AND_TYPE = 12;
    private static final int TAG_METHOD_HANDLE = 15;
    private static final int TAG_METHOD_TYPE = 16;
    private static final int TAG_DYNAMIC = 17;
    private static final int TAG_INVOKE_DYNAMIC = 18;
    private static final int TAG_MODULE = 19;
    private static final int TAG_PACKAGE = 20;

    // access flags
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_PROTECTED = 0x0004;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_FINAL = 0x0010;
    private static final int ACC_SUPER_OR_SYNCHRONIZED = 0x0020;
    private static final int ACC_VOLATILE_OR_BRIDGE = 0x0040;
    private static final int ACC_TRANSIENT_OR_VARARGS = 0x0080;
    private static final int ACC_NATIVE = 0x0100;
    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_STRICT = 0x0800;
    private static final int ACC_SYNTHETIC = 0x1000;
    private static final int ACC_ANNOTATION = 0x2000;
    private static final int ACC_ENUM = 0x4000;
    private static final int ACC_MODULE = 0x8000;

    private ClassFileParser() {
    }

    /** Whether the bytes are a class file (magic CAFEBABE). */
    public static boolean isClassFile(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && ((bytes[0] & 0xff) == 0xCA)
                && ((bytes[1] & 0xff) == 0xFE)
                && ((bytes[2] & 0xff) == 0xBA)
                && ((bytes[3] & 0xff) == 0xBE);
    }

    /**
     * Parse class bytecode into a structured text view.
     *
     * @param bytes    full class-file bytes
     * @param filePath file path used for display
     * @return javap-style structure text
     * @throws IllegalArgumentException if not a class file or parsing fails
     */
    public static String parse(byte[] bytes, String filePath) {
        if (!isClassFile(bytes)) {
            throw new IllegalArgumentException("Not a valid class file (missing CAFEBABE magic)");
        }
        try {
            return new Parser(bytes, filePath).parse();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse class file: " + e.getMessage(), e);
        }
    }

    // ==================== internals ====================

    /** Constant-pool entry. */
    private static final class CpEntry {
        int tag;
        // Shared payload
        long longValue;        // Integer/Long/Float(bits)/Double(bits)
        int idx1;              // Class.name_index / String.string_index / ref.class_index / NameAndType.name_index, etc.
        int idx2;              // ref.name_and_type_index / NameAndType.descriptor_index / MethodHandle.reference_index
        int refKind;           // MethodHandle.reference_kind
        String utf8;           // Utf8
    }

    private static final class MemberInfo {
        int accessFlags;
        String name;
        String descriptor;
        String constantValue;   // field ConstantValue
        Integer codeMaxStack;   // method Code
        Integer codeMaxLocals;
        Long codeLength;
        List<String> exceptions = new ArrayList<>(); // method Exceptions
        List<String> attributeNames = new ArrayList<>();
    }

    private static final class Parser {
        private final String filePath;
        private final DataInputStream in;
        private CpEntry[] cp;

        Parser(byte[] bytes, String filePath) {
            this.filePath = filePath;
            this.in = new DataInputStream(new ByteArrayInputStream(bytes));
        }

        String parse() throws IOException {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IllegalArgumentException("Not a valid class file");
            }
            int minor = in.readUnsignedShort();
            int major = in.readUnsignedShort();

            readConstantPool();

            int accessFlags = in.readUnsignedShort();
            int thisClass = in.readUnsignedShort();
            int superClass = in.readUnsignedShort();

            int interfaceCount = in.readUnsignedShort();
            List<String> interfaces = new ArrayList<>();
            for (int i = 0; i < interfaceCount; i++) {
                interfaces.add(classNameOf(in.readUnsignedShort()));
            }

            int fieldCount = in.readUnsignedShort();
            List<MemberInfo> fields = new ArrayList<>();
            for (int i = 0; i < fieldCount; i++) {
                fields.add(readMember(false));
            }

            int methodCount = in.readUnsignedShort();
            List<MemberInfo> methods = new ArrayList<>();
            for (int i = 0; i < methodCount; i++) {
                methods.add(readMember(true));
            }

            // Class attributes
            String sourceFile = null;
            String signature = null;
            List<String> classAttributes = new ArrayList<>();
            int attrCount = in.readUnsignedShort();
            for (int i = 0; i < attrCount; i++) {
                int nameIdx = in.readUnsignedShort();
                long len = in.readInt() & 0xffffffffL;
                String attrName = utf8Of(nameIdx);
                classAttributes.add(attrName);
                if ("SourceFile".equals(attrName) && len == 2) {
                    sourceFile = utf8Of(in.readUnsignedShort());
                } else if ("Signature".equals(attrName) && len == 2) {
                    signature = utf8Of(in.readUnsignedShort());
                } else {
                    skipFully(len);
                }
            }

            return render(filePath, major, minor, accessFlags,
                    classNameOf(thisClass), superClass == 0 ? null : classNameOf(superClass),
                    interfaces, fields, methods, sourceFile, signature, classAttributes);
        }

        private void readConstantPool() throws IOException {
            int count = in.readUnsignedShort();
            cp = new CpEntry[count];
            for (int i = 1; i < count; i++) {
                CpEntry e = new CpEntry();
                e.tag = in.readUnsignedByte();
                switch (e.tag) {
                    case TAG_UTF8 -> {
                        int len = in.readUnsignedShort();
                        byte[] buf = new byte[len];
                        in.readFully(buf);
                        e.utf8 = decodeModifiedUtf8(buf);
                    }
                    case TAG_INTEGER -> e.longValue = in.readInt();
                    case TAG_FLOAT -> e.longValue = in.readInt() & 0xffffffffL;
                    case TAG_LONG -> {
                        e.longValue = in.readLong();
                        cp[i] = e;
                        i++; // long occupies two slots
                        continue;
                    }
                    case TAG_DOUBLE -> {
                        e.longValue = in.readLong();
                        cp[i] = e;
                        i++; // double occupies two slots
                        continue;
                    }
                    case TAG_CLASS, TAG_STRING, TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE ->
                            e.idx1 = in.readUnsignedShort();
                    case TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF, TAG_NAME_AND_TYPE,
                         TAG_DYNAMIC, TAG_INVOKE_DYNAMIC -> {
                        e.idx1 = in.readUnsignedShort();
                        e.idx2 = in.readUnsignedShort();
                    }
                    case TAG_METHOD_HANDLE -> {
                        e.refKind = in.readUnsignedByte();
                        e.idx1 = in.readUnsignedShort();
                    }
                    default -> throw new IOException("Unknown constant-pool tag: " + e.tag + " (index " + i + ")");
                }
                cp[i] = e;
            }
        }

        private MemberInfo readMember(boolean isMethod) throws IOException {
            MemberInfo m = new MemberInfo();
            m.accessFlags = in.readUnsignedShort();
            m.name = utf8Of(in.readUnsignedShort());
            m.descriptor = utf8Of(in.readUnsignedShort());
            int attrCount = in.readUnsignedShort();
            for (int i = 0; i < attrCount; i++) {
                int nameIdx = in.readUnsignedShort();
                long len = in.readInt() & 0xffffffffL;
                String attrName = utf8Of(nameIdx);
                m.attributeNames.add(attrName);
                if (!isMethod && "ConstantValue".equals(attrName) && len == 2) {
                    m.constantValue = constantToString(in.readUnsignedShort());
                } else if (isMethod && "Code".equals(attrName)) {
                    m.codeMaxStack = in.readUnsignedShort();
                    m.codeMaxLocals = in.readUnsignedShort();
                    long codeLen = in.readInt() & 0xffffffffL;
                    m.codeLength = codeLen;
                    skipFully(codeLen);
                    int excTableLen = in.readUnsignedShort();
                    skipFully(excTableLen * 8L);
                    int subAttrCount = in.readUnsignedShort();
                    for (int j = 0; j < subAttrCount; j++) {
                        in.readUnsignedShort();
                        long subLen = in.readInt() & 0xffffffffL;
                        skipFully(subLen);
                    }
                } else if (isMethod && "Exceptions".equals(attrName)) {
                    int excCount = in.readUnsignedShort();
                    for (int j = 0; j < excCount; j++) {
                        m.exceptions.add(classNameOf(in.readUnsignedShort()));
                    }
                } else {
                    skipFully(len);
                }
            }
            return m;
        }

        // ---------- render ----------

        private String render(String path, int major, int minor, int accessFlags,
                              String thisClass, String superClass, List<String> interfaces,
                              List<MemberInfo> fields, List<MemberInfo> methods,
                              String sourceFile, String signature, List<String> classAttributes) {
            StringBuilder sb = new StringBuilder(4096);
            sb.append("/*\n");
            sb.append(" * Class file structure view (generated from bytecode, read-only)\n");
            sb.append(" * File: ").append(path).append('\n');
            sb.append(" * Version: ").append(javaVersionOf(major))
                    .append(" (major ").append(major).append(", minor ").append(minor).append(")\n");
            if (sourceFile != null) {
                sb.append(" * Compiled from: ").append(sourceFile).append('\n');
            }
            sb.append(" * Constant pool: ").append(cp.length - 1).append(" entries (full list at the end)\n");
            sb.append(" */\n");

            // Class declaration
            sb.append(classAccessToString(accessFlags)).append(thisClass);
            boolean isInterface = (accessFlags & ACC_INTERFACE) != 0;
            if (superClass != null && !isInterface && !"java.lang.Object".equals(superClass)) {
                sb.append("\n    extends ").append(superClass);
            }
            if (!interfaces.isEmpty()) {
                sb.append(isInterface ? "\n    extends " : "\n    implements ")
                        .append(String.join(", ", interfaces));
            }
            sb.append(" {\n\n");

            // Fields
            sb.append("  // ==================== fields (").append(fields.size()).append(") ====================\n\n");
            if (fields.isEmpty()) {
                sb.append("  // (none)\n\n");
            }
            for (MemberInfo f : fields) {
                sb.append("  ").append(fieldToString(f)).append('\n');
            }
            if (!fields.isEmpty()) sb.append('\n');

            // Methods
            sb.append("  // ==================== methods (").append(methods.size()).append(") ====================\n\n");
            if (methods.isEmpty()) {
                sb.append("  // (none)\n");
            }
            String simpleClassName = thisClass.contains(".")
                    ? thisClass.substring(thisClass.lastIndexOf('.') + 1) : thisClass;
            for (MemberInfo m : methods) {
                sb.append("  ").append(methodToString(m, simpleClassName)).append('\n');
                if (m.codeLength != null) {
                    int argsSize = countArgsSize(m);
                    sb.append("    // Code: stack=").append(m.codeMaxStack)
                            .append(", locals=").append(m.codeMaxLocals)
                            .append(", args_size=").append(argsSize)
                            .append(", code_length=").append(m.codeLength).append(" bytes\n");
                }
                sb.append('\n');
            }
            sb.append("}\n");

            // Class attributes
            sb.append("\n/*\n * ==================== class attributes ====================\n");
            for (String attr : classAttributes) {
                sb.append(" * - ").append(attr);
                if ("SourceFile".equals(attr) && sourceFile != null) sb.append(": \"").append(sourceFile).append('"');
                if ("Signature".equals(attr) && signature != null) sb.append(": ").append(signature);
                sb.append('\n');
            }
            if (classAttributes.isEmpty()) sb.append(" * (none)\n");

            // Constant pool
            sb.append(" *\n * ==================== constant pool (").append(cp.length - 1).append(" entries) ====================\n");
            for (int i = 1; i < cp.length; i++) {
                CpEntry e = cp[i];
                if (e == null) continue; // long/double second slot
                sb.append(" * ").append(pad("#" + i, 6)).append("= ").append(cpEntryToString(e)).append('\n');
            }
            sb.append(" */\n");
            return sb.toString();
        }

        private String fieldToString(MemberInfo f) {
            StringBuilder sb = new StringBuilder();
            sb.append(fieldAccessToString(f.accessFlags));
            sb.append(descriptorTypeToJava(f.descriptor)).append(' ').append(f.name);
            if (f.constantValue != null) {
                sb.append(" = ").append(f.constantValue);
            }
            sb.append(';');
            return sb.toString();
        }

        private String methodToString(MemberInfo m, String simpleClassName) {
            if ("<clinit>".equals(m.name)) {
                return "static {};";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(methodAccessToString(m.accessFlags));
            if ("<init>".equals(m.name)) {
                // Constructor: use the simple class name
                sb.append(simpleClassName).append('(').append(methodParamTypes(m.descriptor)).append(')');
            } else {
                sb.append(methodReturnType(m.descriptor)).append(' ')
                        .append(m.name).append('(').append(methodParamTypes(m.descriptor)).append(')');
            }
            if (!m.exceptions.isEmpty()) {
                sb.append(" throws ").append(String.join(", ", m.exceptions));
            }
            sb.append(';');
            return sb.toString();
        }

        private int countArgsSize(MemberInfo m) {
            int size = (m.accessFlags & ACC_STATIC) == 0 ? 1 : 0;
            String desc = m.descriptor;
            int i = 1; // skip '('
            while (i < desc.length() && desc.charAt(i) != ')') {
                int[] consumed = new int[1];
                skipDescriptorType(desc, i, consumed);
                char c = desc.charAt(i);
                size += (c == 'J' || c == 'D') ? 2 : 1;
                i = consumed[0];
            }
            return size;
        }

        // ---------- constant-pool lookup ----------

        private String utf8Of(int idx) {
            if (idx <= 0 || idx >= cp.length || cp[idx] == null) return "#" + idx;
            return cp[idx].utf8 != null ? cp[idx].utf8 : "#" + idx;
        }

        private String classNameOf(int idx) {
            if (idx <= 0 || idx >= cp.length || cp[idx] == null) return "#" + idx;
            CpEntry e = cp[idx];
            if (e.tag != TAG_CLASS) return "#" + idx;
            String internal = utf8Of(e.idx1);
            if (internal.startsWith("[")) {
                return descriptorTypeToJava(internal); // array class
            }
            return internal.replace('/', '.');
        }

        private String constantToString(int idx) {
            if (idx <= 0 || idx >= cp.length || cp[idx] == null) return "#" + idx;
            CpEntry e = cp[idx];
            return switch (e.tag) {
                case TAG_INTEGER -> String.valueOf((int) e.longValue);
                case TAG_FLOAT -> String.valueOf(Float.intBitsToFloat((int) e.longValue));
                case TAG_LONG -> e.longValue + "L";
                case TAG_DOUBLE -> String.valueOf(Double.longBitsToDouble(e.longValue)) + "d";
                case TAG_STRING -> "\"" + escape(utf8Of(e.idx1)) + "\"";
                default -> "#" + idx;
            };
        }

        private String cpEntryToString(CpEntry e) {
            return switch (e.tag) {
                case TAG_UTF8 -> pad("Utf8", 22) + escape(e.utf8);
                case TAG_INTEGER -> pad("Integer", 22) + (int) e.longValue;
                case TAG_FLOAT -> pad("Float", 22) + Float.intBitsToFloat((int) e.longValue) + "f";
                case TAG_LONG -> pad("Long", 22) + e.longValue + "l";
                case TAG_DOUBLE -> pad("Double", 22) + Double.longBitsToDouble(e.longValue) + "d";
                case TAG_CLASS -> pad("Class", 22) + "#" + e.idx1 + pad("", 2) + "// " + utf8Of(e.idx1);
                case TAG_STRING -> pad("String", 22) + "#" + e.idx1 + pad("", 2) + "// " + escape(utf8Of(e.idx1));
                case TAG_FIELDREF -> pad("Fieldref", 22) + "#" + e.idx1 + ".#" + e.idx2 + refComment(e.idx1, e.idx2);
                case TAG_METHODREF -> pad("Methodref", 22) + "#" + e.idx1 + ".#" + e.idx2 + refComment(e.idx1, e.idx2);
                case TAG_INTERFACE_METHODREF ->
                        pad("InterfaceMethodref", 22) + "#" + e.idx1 + ".#" + e.idx2 + refComment(e.idx1, e.idx2);
                case TAG_NAME_AND_TYPE ->
                        pad("NameAndType", 22) + "#" + e.idx1 + ":#" + e.idx2 + pad("", 2) + "// " + utf8Of(e.idx1) + ":" + utf8Of(e.idx2);
                case TAG_METHOD_HANDLE -> pad("MethodHandle", 22) + e.refKind + ":#" + e.idx1;
                case TAG_METHOD_TYPE -> pad("MethodType", 22) + "#" + e.idx1 + pad("", 2) + "// " + utf8Of(e.idx1);
                case TAG_DYNAMIC -> pad("Dynamic", 22) + "#" + e.idx1 + ":#" + e.idx2;
                case TAG_INVOKE_DYNAMIC -> pad("InvokeDynamic", 22) + "#" + e.idx1 + ":#" + e.idx2;
                case TAG_MODULE -> pad("Module", 22) + "#" + e.idx1 + pad("", 2) + "// " + utf8Of(e.idx1);
                case TAG_PACKAGE -> pad("Package", 22) + "#" + e.idx1 + pad("", 2) + "// " + utf8Of(e.idx1);
                default -> "tag " + e.tag;
            };
        }

        private String refComment(int classIdx, int ntIdx) {
            String cls = classNameOf(classIdx);
            String name = "?";
            String desc = "?";
            if (ntIdx > 0 && ntIdx < cp.length && cp[ntIdx] != null && cp[ntIdx].tag == TAG_NAME_AND_TYPE) {
                name = utf8Of(cp[ntIdx].idx1);
                desc = utf8Of(cp[ntIdx].idx2);
            }
            return "  // " + cls + "." + name + ":" + desc;
        }

        // ---------- descriptor conversion ----------

        /** Field/type descriptor → Java type name, e.g. Ljava/lang/String; → java.lang.String, [[I → int[][] */
        static String descriptorTypeToJava(String desc) {
            StringBuilder sb = new StringBuilder();
            int[] pos = new int[1];
            String type = readType(desc, 0, pos);
            if (type == null) return desc;
            sb.append(type);
            return sb.toString();
        }

        private String methodReturnType(String desc) {
            int idx = desc.indexOf(')');
            if (idx < 0) return desc;
            return readType(desc, idx + 1, new int[1]);
        }

        private String methodParamTypes(String desc) {
            List<String> params = new ArrayList<>();
            int i = 1; // skip '('
            while (i < desc.length() && desc.charAt(i) != ')') {
                int[] pos = new int[1];
                String t = readType(desc, i, pos);
                if (t == null) break;
                params.add(t);
                i = pos[0];
            }
            return String.join(", ", params);
        }

        /** Read one type from desc[start]; returns the Java type name and writes the next index to pos[0]. */
        private static String readType(String desc, int start, int[] pos) {
            int i = start;
            int arrayDepth = 0;
            while (i < desc.length() && desc.charAt(i) == '[') {
                arrayDepth++;
                i++;
            }
            if (i >= desc.length()) return null;
            char c = desc.charAt(i);
            String base;
            switch (c) {
                case 'B' -> { base = "byte"; i++; }
                case 'C' -> { base = "char"; i++; }
                case 'D' -> { base = "double"; i++; }
                case 'F' -> { base = "float"; i++; }
                case 'I' -> { base = "int"; i++; }
                case 'J' -> { base = "long"; i++; }
                case 'S' -> { base = "short"; i++; }
                case 'Z' -> { base = "boolean"; i++; }
                case 'V' -> { base = "void"; i++; }
                case 'L' -> {
                    int end = desc.indexOf(';', i);
                    if (end < 0) return null;
                    base = desc.substring(i + 1, end).replace('/', '.');
                    i = end + 1;
                }
                default -> { return null; }
            }
            pos[0] = i;
            return base + "[]".repeat(arrayDepth);
        }

        private static void skipDescriptorType(String desc, int start, int[] consumed) {
            int i = start;
            while (i < desc.length() && desc.charAt(i) == '[') i++;
            if (i < desc.length() && desc.charAt(i) == 'L') {
                int end = desc.indexOf(';', i);
                i = end < 0 ? desc.length() : end + 1;
            } else {
                i++;
            }
            consumed[0] = i;
        }

        // ---------- access flags ----------

        private static String classAccessToString(int flags) {
            StringBuilder sb = new StringBuilder();
            if ((flags & ACC_PUBLIC) != 0) sb.append("public ");
            if ((flags & ACC_FINAL) != 0) sb.append("final ");
            if ((flags & ACC_ABSTRACT) != 0 && (flags & ACC_INTERFACE) == 0) sb.append("abstract ");
            if ((flags & ACC_ANNOTATION) != 0) sb.append("@interface ");
            else if ((flags & ACC_INTERFACE) != 0) sb.append("interface ");
            else if ((flags & ACC_ENUM) != 0) sb.append("enum ");
            else if ((flags & ACC_MODULE) != 0) sb.append("module ");
            else sb.append("class ");
            return sb.toString();
        }

        private static String fieldAccessToString(int flags) {
            StringBuilder sb = new StringBuilder();
            if ((flags & ACC_PUBLIC) != 0) sb.append("public ");
            if ((flags & ACC_PRIVATE) != 0) sb.append("private ");
            if ((flags & ACC_PROTECTED) != 0) sb.append("protected ");
            if ((flags & ACC_STATIC) != 0) sb.append("static ");
            if ((flags & ACC_FINAL) != 0) sb.append("final ");
            if ((flags & ACC_VOLATILE_OR_BRIDGE) != 0) sb.append("volatile ");
            if ((flags & ACC_TRANSIENT_OR_VARARGS) != 0) sb.append("transient ");
            return sb.toString();
        }

        private static String methodAccessToString(int flags) {
            StringBuilder sb = new StringBuilder();
            if ((flags & ACC_PUBLIC) != 0) sb.append("public ");
            if ((flags & ACC_PRIVATE) != 0) sb.append("private ");
            if ((flags & ACC_PROTECTED) != 0) sb.append("protected ");
            if ((flags & ACC_STATIC) != 0) sb.append("static ");
            if ((flags & ACC_FINAL) != 0) sb.append("final ");
            if ((flags & ACC_SUPER_OR_SYNCHRONIZED) != 0) sb.append("synchronized ");
            if ((flags & ACC_VOLATILE_OR_BRIDGE) != 0) sb.append("/* bridge */ ");
            if ((flags & ACC_TRANSIENT_OR_VARARGS) != 0) sb.append("/* varargs */ ");
            if ((flags & ACC_NATIVE) != 0) sb.append("native ");
            if ((flags & ACC_ABSTRACT) != 0) sb.append("abstract ");
            if ((flags & ACC_STRICT) != 0) sb.append("strictfp ");
            return sb.toString();
        }

        // ---------- utils ----------

        private static String javaVersionOf(int major) {
            // 45=1.1, 46=1.2, 47=1.3, 48=1.4, 49=5 ... 52=8, 55=11, 61=17 ...
            if (major >= 49) return "Java " + (major - 44);
            if (major >= 45) return "Java 1." + (major - 44);
            return "Java 1.0";
        }

        private static String pad(String s, int width) {
            if (s.length() >= width) return s + " ";
            return s + " ".repeat(width - s.length());
        }

        private static String escape(String s) {
            if (s == null) return "";
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            return sb.toString();
        }

        /** Class-file UTF-8 is modified UTF-8; decode as standard UTF-8 (tolerant of NUL and similar edge cases). */
        private static String decodeModifiedUtf8(byte[] buf) {
            try {
                return new String(buf, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return new String(buf, StandardCharsets.ISO_8859_1);
            }
        }

        private void skipFully(long n) throws IOException {
            long remaining = n;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    if (in.read() < 0) throw new IOException("Incomplete class file");
                    skipped = 1;
                }
                remaining -= skipped;
            }
        }
    }
}
