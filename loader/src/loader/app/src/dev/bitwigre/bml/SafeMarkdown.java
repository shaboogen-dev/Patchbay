package dev.bitwigre.bml;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** A mod's README as text: headings, paragraphs, lists, quotes, code, links and images, never raw HTML.
 * An image is shown only when its path is relative and stays inside the mod's folder, and the file is a
 * PNG, JPEG or GIF (a GIF may animate); anything else, a web image included, shows its alt text, so
 * nothing is ever fetched from the network. Images are scaled down to the reading column, never up.
 * A link opens in the web browser after "Open this link?". Common Markdown blocks and inline forms,
 * not full CommonMark.
 */
final class SafeMarkdown {
    /** The widest an image is shown, in logical pixels: the 820 px reading column less the view's 32 px sides. */
    static final int IMAGE_WIDTH=820-2*32;
    private static final long IMAGE_PIXELS=40_000_000L;
    private static final Set<String> IMAGE_FORMATS=Set.of("png","jpeg","gif");
    private static final Pattern ITEM = Pattern.compile("^\\s*[-*+]\\s+(.+)$");
    private static final Pattern ENUM = Pattern.compile("^\\s*[0-9]+[.)]\\s+(.+)$");
    private static final Pattern TOKEN = Pattern.compile("(!?)\\[([^]\\n]{0,400})\\]\\(([^)\\n]{1,2000})\\)");
    private static final Pattern IMAGE_LINE = Pattern.compile("!\\[[^]\\n]{0,400}\\]\\([^)\\n]{1,2000}\\)");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*|__([^_]+)__");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)|(?<!_)_([^_\\n]+)_(?!_)");

    /** Where a README's images may come from: paths are relative to the README's folder and must stay
     * inside {@code root} (the mod's or component's folder). {@code scale} is the display's scaling, so
     * an image is never shown larger than its own pixels. */
    record Images(Path readmeFolder,Path root,double scale) {}

    static JEditorPane view(String source,Images images) {
        JEditorPane p=new JEditorPane("text/html",toHtml(source,images)){
            @Override protected void paintComponent(Graphics g){
                // Images drawn smaller than their pixels are filtered, not sampled.
                if(g instanceof Graphics2D g2)g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                super.paintComponent(g);
            }
        };
        p.setEditable(false);p.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,Boolean.TRUE);
        p.setFont(PatchbayStyle.ui(15));
        p.setBorder(BorderFactory.createEmptyBorder(24,32,32,32));
        p.setBackground(PatchbayStyle.WELL);p.setForeground(PatchbayStyle.TEXT);
        p.setCaretPosition(0);
        p.addHyperlinkListener(e -> {
            if(e.getEventType()!=HyperlinkEvent.EventType.ACTIVATED)return;
            try {
                URI u=e.getURL()==null?URI.create(e.getDescription()):e.getURL().toURI();
                if(!Set.of("https","http").contains(u.getScheme()==null?"":u.getScheme().toLowerCase(Locale.ROOT)))return;
                if(PromptDialog.confirm(SwingUtilities.getWindowAncestor(p),"Open this link?",
                    u.toString(),"It opens in your web browser.","Open Link"))
                    if(Desktop.isDesktopSupported())Desktop.getDesktop().browse(u);
            }catch(Exception ignored){ }
        });
        return p;
    }
    /** The README as HTML with no images (each shows its alt text). */
    static String toHtml(String source) {return toHtml(source,null);}
    static String toHtml(String source,Images images) {
        String ui=PatchbayStyle.ui(15).getFamily(),mono=PatchbayStyle.mono(13).getFamily();
        // Swing's own style sheet wins over a tag rule here (Monospaced for <code> and <pre>, a 50 px
        // list indent), so those elements carry a class ("m", "l"), which wins.
        StringBuilder out=new StringBuilder("<html><head><style>body{font-family:"+ui+";color:#E4E5E7;background:#1C1D20;margin:0;font-size:15pt;}"
            +" h1,h2,h3,h4{color:#E4E5E7;margin-top:14px;margin-bottom:8px;} h1{font-size:22pt;} h2{font-size:18pt;} h3{font-size:16pt;} h4{font-size:15pt;}"
            +" p{margin-top:0;margin-bottom:10px;} li{margin-bottom:3px;} a{color:#2EC4B6;}"
            +" .l{margin-top:0;margin-bottom:10px;margin-left-ltr:28px;margin-right-rtl:28px;}"
            +" .m{font-family:"+mono+";font-size:12pt;color:#C9CBCF;background:#121315;} pre{padding:10px;}"
            +" .figure{margin-top:4px;margin-bottom:14px;} .alt{color:#9C9FA5;}"
            +" blockquote{color:#9C9FA5;margin-left:12px;} hr{color:#3B3D42;}</style></head><body>");
        boolean fence=false, unordered=false, ordered=false, paragraph=false;
        for(String line:source.replace("\r\n","\n").replace('\r','\n').split("\n",-1)) {
            String trim=line.strip();
            if(trim.startsWith("```")||trim.startsWith("~~~")) {
                if(paragraph){out.append("</p>");paragraph=false;}
                if(unordered){out.append("</ul>");unordered=false;}
                if(ordered){out.append("</ol>");ordered=false;}
                out.append(fence?"</pre>":"<pre class=\"m\">");fence=!fence;continue;
            }
            if(fence){out.append(esc(line)).append("\n");continue;}
            if(trim.isEmpty()) {
                if(paragraph){out.append("</p>");paragraph=false;}
                if(unordered){out.append("</ul>");unordered=false;}
                if(ordered){out.append("</ol>");ordered=false;}
                continue;
            }
            Matcher bullets=ITEM.matcher(line), numbered=ENUM.matcher(line);
            if(bullets.matches()||numbered.matches()) {
                if(paragraph){out.append("</p>");paragraph=false;}
                boolean un=bullets.matches();
                if(un&&ordered){out.append("</ol>");ordered=false;}
                if(!un&&unordered){out.append("</ul>");unordered=false;}
                if(un&&!unordered){out.append("<ul class=\"l\">");unordered=true;}
                if(!un&&!ordered){out.append("<ol class=\"l\">");ordered=true;}
                out.append("<li>").append(inline(un?bullets.group(1):numbered.group(1),images)).append("</li>");continue;
            }
            if(unordered){out.append("</ul>");unordered=false;}
            if(ordered){out.append("</ol>");ordered=false;}
            if(IMAGE_LINE.matcher(trim).matches()) {
                // An image on its own line is a block of its own; one that can't be shown and has no
                // alt text leaves nothing behind (no empty paragraph).
                if(paragraph){out.append("</p>");paragraph=false;}
                String figure=inline(trim,images);
                if(!figure.isEmpty())out.append("<p class=\"figure\">").append(figure).append("</p>");
                continue;
            }
            if(trim.matches("#{1,6}\\s+.*")) {
                if(paragraph){out.append("</p>");paragraph=false;}
                int n=0;while(n<trim.length()&&trim.charAt(n)=='#')n++;
                n=Math.min(4,n);out.append("<h").append(n).append(">").append(inline(trim.substring(trim.indexOf(' ')+1),images)).append("</h").append(n).append(">");continue;
            }
            if(trim.matches("(-{3,}|\\*{3,}|_{3,})")) {
                if(paragraph){out.append("</p>");paragraph=false;}
                out.append("<hr>");continue;
            }
            if(trim.startsWith("> ")) {
                if(paragraph){out.append("</p>");paragraph=false;}
                out.append("<blockquote>").append(inline(trim.substring(2),images)).append("</blockquote>");continue;
            }
            if(!paragraph){out.append("<p>");paragraph=true;} else out.append(' ');
            out.append(inline(trim,images));
        }
        if(fence)out.append("</pre>");if(paragraph)out.append("</p>");
        if(unordered)out.append("</ul>");if(ordered)out.append("</ol>");
        return out.append("</body></html>").toString();
    }
    private static String inline(String text,Images images) {
        // Replace link/image spans first using placeholders so author input and URLs
        // never become raw HTML; every emitted tag is created here.
        Matcher m=TOKEN.matcher(text);StringBuilder all=new StringBuilder();int at=0;
        ArrayList<String> placeholders=new ArrayList<>();
        while(m.find()) {
            all.append(text,at,m.start());
            String display=esc(m.group(2));String url=m.group(3).trim();String tag=display;
            if("!".equals(m.group(1))) tag=image(display,url,images);
            else {
                try {
                    URI uri=URI.create(url);
                    String scheme=uri.getScheme();
                    if(scheme!=null&&Set.of("http","https").contains(scheme.toLowerCase(Locale.ROOT)))
                        tag="<a href=\""+escAttr(uri.toASCIIString())+"\">"+display+"</a>";
                }catch(Exception ignored) {}
            }
            int index=placeholders.size();placeholders.add(tag);all.append("ZZBMLMARKDOWNPLACEHOLDER").append(index).append("ZZ");
            at=m.end();
        }
        all.append(text.substring(at));String result=esc(all.toString());
        Matcher code=CODE.matcher(result);result=code.replaceAll("<code class=\"m\">$1</code>");
        Matcher bold=BOLD.matcher(result);StringBuffer b=new StringBuffer();
        while(bold.find())bold.appendReplacement(b,Matcher.quoteReplacement("<b>"+(bold.group(1)!=null?bold.group(1):bold.group(2))+"</b>"));bold.appendTail(b);result=b.toString();
        Matcher italic=ITALIC.matcher(result);b=new StringBuffer();
        while(italic.find())italic.appendReplacement(b,Matcher.quoteReplacement("<i>"+(italic.group(1)!=null?italic.group(1):italic.group(2))+"</i>"));italic.appendTail(b);result=b.toString();
        for(int i=0;i<placeholders.size();i++)result=result.replace("ZZBMLMARKDOWNPLACEHOLDER"+i+"ZZ",placeholders.get(i));
        return result;
    }

    /** An {@code <img>} for a local image that may be shown, or else the (escaped) alt text. */
    private static String image(String alt,String target,Images images) {
        String fallback=alt.isEmpty()?"":"<span class=\"alt\">"+alt+"</span>";
        if(images==null)return fallback;
        Path file=localImage(target,images);
        Dimension px=file==null?null:imageSize(file);
        if(px==null)return fallback;
        double scale=Math.max(1.0,images.scale());
        double w=px.width/scale,h=px.height/scale;
        if(w>IMAGE_WIDTH){h=h*IMAGE_WIDTH/w;w=IMAGE_WIDTH;}
        String src;
        try{src=file.toUri().toURL().toExternalForm();}catch(Exception ex){return fallback;}
        return "<img src=\""+escAttr(src)+"\" width=\""+Math.max(1,(int)Math.round(w))+"\" height=\""+Math.max(1,(int)Math.round(h))
            +"\" alt=\""+alt+"\" border=\"0\">";
    }
    /** The file a README image path names, or null unless it is relative, stays inside the root and
     * has a PNG, JPEG or GIF name. URLs, absolute paths, backslashes and {@code ..} are refused. */
    static Path localImage(String target,Images images) {
        String t=target.strip();
        if(t.startsWith("<")){int end=t.indexOf('>');if(end<0)return null;t=t.substring(1,end);}
        else {int space=t.indexOf(' ');if(space>=0)t=t.substring(0,space);}  // drop a "title"
        if(t.isEmpty()||t.indexOf(':')>=0||t.indexOf('\\')>=0||t.startsWith("/")||t.indexOf('?')>=0||t.indexOf('#')>=0)return null;
        String path=percentDecode(t);
        if(path==null||path.isEmpty()||path.indexOf(':')>=0||path.indexOf('\\')>=0||path.startsWith("/")||path.indexOf('\0')>=0)return null;
        for(String part:path.split("/"))if(part.equals(".."))return null;
        String lower=path.toLowerCase(Locale.ROOT);
        if(!(lower.endsWith(".png")||lower.endsWith(".jpg")||lower.endsWith(".jpeg")||lower.endsWith(".gif")))return null;
        try {
            Path root=images.root().toAbsolutePath().normalize();
            Path file=images.readmeFolder().toAbsolutePath().normalize().resolve(path).normalize();
            if(!file.startsWith(root)||!Files.isRegularFile(file))return null;
            // A link or junction may not lead outside the folder either.
            if(!file.toRealPath().startsWith(root.toRealPath()))return null;
            return file;
        }catch(Exception ex){return null;}
    }
    /** The image's size in pixels when its content is PNG, JPEG or GIF, read from its header only. */
    static Dimension imageSize(Path file) {
        try(ImageInputStream in=ImageIO.createImageInputStream(file.toFile())) {
            if(in==null)return null;
            Iterator<ImageReader> readers=ImageIO.getImageReaders(in);
            if(!readers.hasNext())return null;
            ImageReader r=readers.next();
            try {
                if(!IMAGE_FORMATS.contains(r.getFormatName().toLowerCase(Locale.ROOT)))return null;
                r.setInput(in,true,true);
                int w=r.getWidth(0),h=r.getHeight(0);
                if(w<=0||h<=0||(long)w*h>IMAGE_PIXELS)return null;
                return new Dimension(w,h);
            } finally {r.dispose();}
        }catch(Exception ex){return null;}
    }
    private static String percentDecode(String s) {
        if(s.indexOf('%')<0)return s;
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        for(int i=0;i<s.length();) {
            char c=s.charAt(i);
            if(c=='%') {
                if(i+2>=s.length())return null;
                int hi=Character.digit(s.charAt(i+1),16),lo=Character.digit(s.charAt(i+2),16);
                if(hi<0||lo<0)return null;
                b.write(hi*16+lo);i+=3;
            } else {
                int cp=s.codePointAt(i);byte[] u=new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                b.write(u,0,u.length);i+=Character.charCount(cp);
            }
        }
        return b.toString(StandardCharsets.UTF_8);
    }
    private static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static String escAttr(String s){return esc(s);}
    static String load(Path p) throws IOException {return Files.readString(p, StandardCharsets.UTF_8);}
}
