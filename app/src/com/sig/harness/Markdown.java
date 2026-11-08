package com.sig.harness;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Tiny Markdown renderer tuned for chat bubbles (code, bold, links, lists). */
public final class Markdown {
    private Markdown() { }

    public static View build(final Context c, String text) {
        LinearLayout root = Ui.column(c);
        root.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (text == null) text = "";
        String[] lines = text.split("\n", -1);
        StringBuilder paragraph = new StringBuilder();
        StringBuilder code = new StringBuilder();
        String codeLang = "";
        boolean inCode = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (!inCode) {
                    flushParagraph(c, root, paragraph);
                    inCode = true;
                    codeLang = trimmed.substring(3).trim();
                    code.setLength(0);
                } else {
                    addCode(c, root, code.toString(), codeLang);
                    inCode = false;
                    code.setLength(0);
                    codeLang = "";
                }
                continue;
            }
            if (inCode) {
                code.append(line).append('\n');
            } else if (trimmed.isEmpty()) {
                flushParagraph(c, root, paragraph);
            } else {
                paragraph.append(line).append('\n');
            }
        }
        if (inCode) addCode(c, root, code.toString(), codeLang);
        flushParagraph(c, root, paragraph);
        if (root.getChildCount() == 0) {
            TextView empty = Ui.text(c, "", 16f, Ui.TEXT_SOFT, Ui.REGULAR);
            root.addView(empty);
        }
        return root;
    }

    private static void flushParagraph(Context c, LinearLayout root, StringBuilder paragraph) {
        if (paragraph.length() == 0) return;
        String value = paragraph.toString();
        paragraph.setLength(0);
        if (value.trim().isEmpty()) return;
        final TextView tv = Ui.text(c, "", 16f, Ui.TEXT_SOFT, Ui.REGULAR);
        tv.setLineSpacing(Ui.dp(c, 4), 1.0f);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = value.split("\n", -1);
        boolean hasLink = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == lines.length - 1 && line.isEmpty()) continue;
            if (line.trim().isEmpty()) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                continue;
            }
            int start = sb.length();
            String trimmed = line.trim();
            if (trimmed.matches("^#{1,6}\\s+.*")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                String body = trimmed.substring(level).trim();
                sb.append(body);
                int end = sb.length();
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(level <= 1 ? 1.22f : 1.12f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(Ui.TEXT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                int indent = line.indexOf(trimmed);
                String bullet = indent >= 2 ? "◦  " : "•  ";
                sb.append(bullet).append(trimmed.substring(2));
                int end = sb.length();
                sb.setSpan(new ForegroundColorSpan(Ui.MUTED), end - trimmed.length() + 1, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("> ")) {
                sb.append("│ ").append(trimmed.substring(2));
                sb.setSpan(new ForegroundColorSpan(Ui.MUTED), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.equals("---") || trimmed.equals("***")) {
                sb.append("────────");
                sb.setSpan(new ForegroundColorSpan(Ui.SURFACE_3), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                if (applyInline(c, sb, line)) hasLink = true;
            }
            if (i < lines.length - 1) sb.append('\n');
        }
        tv.setText(sb);
        tv.setTextColor(Ui.TEXT_SOFT);
        tv.setTextIsSelectable(true);
        if (hasLink) {
            tv.setMovementMethod(LinkMovementMethod.getInstance());
            tv.setHighlightColor(Color.TRANSPARENT);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 10);
        root.addView(tv, lp);
    }

    private static boolean applyInline(final Context c, SpannableStringBuilder sb, String text) {
        boolean hasLink = false;
        int i = 0;
        int boldStart = -1;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == (char) 96) {
                int end = text.indexOf((char) 96, i + 1);
                if (end > i) {
                    int start = sb.length();
                    String code = text.substring(i + 1, end);
                    sb.append(code);
                    int stop = sb.length();
                    sb.setSpan(new TypefaceSpan("monospace"), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(Ui.SURFACE_2), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(Color.rgb(214, 231, 255)), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(0.94f), start, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (ch == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                if (boldStart < 0) {
                    boldStart = sb.length();
                } else {
                    sb.setSpan(new StyleSpan(Typeface.BOLD), boldStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    boldStart = -1;
                }
                i += 2;
                continue;
            }
            if (ch == '[') {
                int close = text.indexOf(']', i + 1);
                if (close > 0 && close + 1 < text.length() && text.charAt(close + 1) == '(') {
                    int paren = text.indexOf(')', close + 2);
                    if (paren > 0) {
                        String label = text.substring(i + 1, close);
                        final String url = text.substring(close + 2, paren).trim();
                        int start = sb.length();
                        sb.append(label);
                        final int end = sb.length();
                        ClickableSpan span = new ClickableSpan() {
                            @Override public void onClick(View widget) {
                                try {
                                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    c.startActivity(intent);
                                } catch (Throwable ignored) { }
                            }
                            @Override public void updateDrawState(TextPaint ds) {
                                ds.setColor(Ui.ACCENT);
                                ds.setUnderlineText(true);
                            }
                        };
                        sb.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        hasLink = true;
                        i = paren + 1;
                        continue;
                    }
                }
            }
            sb.append(ch);
            i++;
        }
        if (boldStart >= 0) {
            sb.setSpan(new StyleSpan(Typeface.BOLD), boldStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return hasLink;
    }

    private static void addCode(final Context c, LinearLayout root, String code, String lang) {
        String body = code == null ? "" : code.trim();
        if (body.isEmpty()) return;
        LinearLayout block = Ui.column(c);
        block.setBackground(Ui.round(Ui.CODE_BG, 12f, c));
        LinearLayout header = Ui.row(c);
        header.setPadding(Ui.dp(c, 14), Ui.dp(c, 9), Ui.dp(c, 10), Ui.dp(c, 4));
        String label = lang == null || lang.isEmpty() ? "código" : lang;
        TextView kind = Ui.text(c, label, 10.5f, Ui.FAINT, Ui.MONO);
        kind.setAllCaps(true);
        header.addView(kind, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView copy = Ui.text(c, "copiar", 11.5f, Ui.ACCENT, Ui.MEDIUM);
        copy.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
        copy.setClickable(true);
        copy.setBackground(Ui.ripple(Color.TRANSPARENT, 8f, c));
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("SIG Harness", code));
                    Toast.makeText(c, "Código copiado", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) { }
            }
        });
        header.addView(copy);
        block.addView(header);
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        TextView tv = Ui.text(c, body, 12.5f, Color.rgb(205, 224, 247), Ui.MONO);
        tv.setLineSpacing(Ui.dp(c, 2), 1.0f);
        tv.setTextIsSelectable(true);
        tv.setPadding(Ui.dp(c, 14), Ui.dp(c, 2), Ui.dp(c, 14), Ui.dp(c, 14));
        scroll.addView(tv, new HorizontalScrollView.LayoutParams(HorizontalScrollView.LayoutParams.WRAP_CONTENT, HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        block.addView(scroll);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 2);
        lp.bottomMargin = Ui.dp(c, 12);
        root.addView(block, lp);
    }
}
