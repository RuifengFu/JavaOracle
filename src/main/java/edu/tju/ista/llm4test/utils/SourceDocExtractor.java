package edu.tju.ista.llm4test.utils;

import com.thoughtworks.qdox.model.DocletTag;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaConstructor;
import com.thoughtworks.qdox.model.JavaMethod;
import com.thoughtworks.qdox.model.JavaParameter;

import java.util.List;

/**
 * 从JDK源码的Javadoc注释中提取文档（替代下载的HTML JavaDoc）。
 * <p>
 * 支持类级别和方法/构造函数级别文档，包含描述与常用标签
 * （@param / @return / @throws / @apiNote / @implSpec / @implNote）。
 */
public final class SourceDocExtractor {

    private SourceDocExtractor() {
    }

    /**
     * 提取类级别文档
     */
    public static String classDoc(JavaClass javaClass) {
        if (javaClass == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("类: ").append(javaClass.getFullyQualifiedName()).append('\n');
        appendDeclaration(sb, javaClass);
        appendComment(sb, javaClass.getComment());
        appendTags(sb, javaClass.getTags(), false);
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 提取方法级别文档
     */
    public static String methodDoc(JavaClass javaClass, JavaMethod method) {
        if (method == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("方法: ").append(javaClass.getFullyQualifiedName()).append('.')
                .append(method.getName()).append(paramTypes(method.getParameters())).append('\n');
        appendComment(sb, method.getComment());
        appendTags(sb, method.getTags(), true);
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 提取构造函数级别文档
     */
    public static String constructorDoc(JavaClass javaClass, JavaConstructor constructor) {
        if (constructor == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("构造函数: ").append(javaClass.getFullyQualifiedName())
                .append(paramTypes(constructor.getParameters())).append('\n');
        appendComment(sb, constructor.getComment());
        appendTags(sb, constructor.getTags(), true);
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 参数列表的类型签名文本：(java.lang.String, int)
     */
    private static String paramTypes(List<JavaParameter> params) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            JavaParameter p = params.get(i);
            sb.append(p.getType().getFullyQualifiedName().replace('$', '.'));
            if (p.isVarArgs()) sb.append("...");
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * 追加类的声明信息（继承/实现），给LLM提供类的结构上下文
     */
    private static void appendDeclaration(StringBuilder sb, JavaClass javaClass) {
        String kind = javaClass.isInterface() ? "接口"
                : javaClass.isEnum() ? "枚举"
                : javaClass.isRecord()? "记录"
                : javaClass.isAbstract() ? "抽象类" : "类";
        sb.append("类型: ").append(kind);
        try {
            if (javaClass.getSuperJavaClass() != null
                    && !"java.lang.Object".equals(javaClass.getSuperJavaClass().getFullyQualifiedName())) {
                sb.append(", 继承: ").append(javaClass.getSuperJavaClass().getFullyQualifiedName());
            }
            List<JavaClass> interfaces = javaClass.getInterfaces();
            if (interfaces != null && !interfaces.isEmpty()) {
                sb.append(", 实现: ");
                for (int i = 0; i < interfaces.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(interfaces.get(i).getFullyQualifiedName());
                }
            }
        } catch (Exception ignored) {
            // 声明信息仅作辅助，解析失败不影响文档
        }
        sb.append('\n');
    }

    private static void appendComment(StringBuilder sb, String comment) {
        if (comment != null && !comment.isBlank()) {
            sb.append(cleanInline(comment.trim())).append('\n');
        }
    }

    private static void appendTags(StringBuilder sb, List<DocletTag> tags, boolean includeParams) {
        if (tags == null) {
            return;
        }
        for (DocletTag tag : tags) {
            if (tag == null) continue;
            String name = tag.getName();
            String value = tag.getValue() == null ? "" : cleanInline(tag.getValue().trim());
            if (value.isEmpty()) continue;
            switch (name) {
                case "param":
                    if (includeParams) sb.append("@param ").append(value).append('\n');
                    break;
                case "return":
                    sb.append("@return ").append(value).append('\n');
                    break;
                case "throws":
                case "exception":
                    sb.append("@throws ").append(value).append('\n');
                    break;
                case "apiNote":
                    sb.append("API注: ").append(value).append('\n');
                    break;
                case "implSpec":
                    sb.append("实现要求: ").append(value).append('\n');
                    break;
                case "implNote":
                    sb.append("实现说明: ").append(value).append('\n');
                    break;
                default:
                    // @since/@see/@code等对失败分析价值低，忽略
            }
        }
    }

    /**
     * 清理javadoc内联标记，让文本对LLM更友好：
     * {@code foo} -> foo, {@link Bar#baz() label} -> label或Bar,
     * {@literal x} -> x, {@index ...} 忽略
     */
    public static String cleanInline(String text) {
        if (text == null) return null;
        String cleaned = text;
        // {@link target label} -> label (if any) else last segment of target
        cleaned = replaceTag(cleaned, "link", 2, true);
        cleaned = replaceTag(cleaned, "linkplain", 2, true);
        cleaned = replaceTag(cleaned, "code", 1, false);
        cleaned = replaceTag(cleaned, "literal", 1, false);
        // {@index term ...} -> 保留term后面的描述
        cleaned = replaceTag(cleaned, "index", 1, false);
        cleaned = cleaned.replaceAll("\\{@value\\}", "对应常量值");
        // 常见HTML实体与标签的轻量清理
        cleaned = cleaned.replace("&nbsp;", " ")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"")
                .replaceAll("</?p>", "\n")
                .replaceAll("</?blockquote>", "")
                .replaceAll("<pre>", "\n").replaceAll("</pre>", "")
                .replaceAll("</?b>|</?i>|</?em>|</?strong>|</?code>", "");
        return cleaned.trim();
    }

    /**
     * 替换{@name ...}形式的内联标签
     *
     * @param argCount      标签期望的参数个数（多余部分视为描述文本）
     * @param preferLastArg {@link}类标签优先取后面的label
     */
    private static String replaceTag(String text, String name, int argCount, boolean preferLastArg) {
        String open = "{@" + name;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            int s = text.indexOf(open, i);
            if (s < 0) break;
            int e = findClose(text, s);
            if (e < 0) break;
            out.append(text, i, s);
            String inner = text.substring(s + open.length(), e).trim();
            String[] parts = inner.split("\\s+");
            String replacement;
            if (parts.length <= argCount) {
                // 只有参数没有描述：取最后一个token并去掉#member
                replacement = lastMeaningful(parts.length > 0 ? parts[parts.length - 1] : "");
            } else {
                if (preferLastArg) {
                    // link target label -> label
                    StringBuilder desc = new StringBuilder();
                    for (int k = argCount; k < parts.length; k++) {
                        if (k > argCount) desc.append(' ');
                        desc.append(parts[k]);
                    }
                    replacement = desc.toString();
                } else {
                    // code内容全部保留
                    replacement = inner;
                }
            }
            out.append(replacement);
            i = e + 1;
        }
        out.append(text.substring(i));
        return out.toString();
    }

    private static int findClose(String text, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String lastMeaningful(String s) {
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        return s;
    }
}