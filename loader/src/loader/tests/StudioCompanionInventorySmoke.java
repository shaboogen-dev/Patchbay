package dev.bitwigre.bml;

import java.nio.file.*;
import java.util.*;

/** Dependency-free inventory/Markdown safety tests; never touches installed Bitwig. */
public final class StudioCompanionInventorySmoke {
    private static void require(boolean b,String reason){if(!b)throw new AssertionError(reason);}
    private static String mod(String id,String extra,String resources) {
        return "{\"manifest_version\":2,\"mod\":{\"id\":\""+id+"\",\"name\":\""+id+"\",\"version\":\"1.0\",\"author\":\"Test\",\"description\":\"Fixture\""+extra+"},"+
               "\"built_against\":{\"bitwig_version\":\"6.1.3\",\"platform\":\"windows\",\"architecture\":\"x64\"},"+
               "\"targets\":{},\"hooks\":[],\"trace_targets\":[{\"id\":\"x\",\"class\":\"demo.Bridge\",\"method\":\"run\",\"descriptor\":\"()V\"}]"+resources+"}";
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("bml-studio-inventory-test-");
        try {
            Path mods=root.resolve("mods");Files.createDirectories(mods);
            Path okay=Files.createDirectories(mods.resolve("okay"));
            Files.writeString(okay.resolve("README.md"),"# Example\n\nNormal **bold** text.\n");
            Files.writeString(okay.resolve("mod.json"),mod("okay",",\"documentation\":\"readme\",\"tags\":[\"Utility\",\"utility\"]",
                ",\"resources\":{\"readme\":{\"kind\":\"file\",\"path\":\"README.md\"}}"));
            Path missing=Files.createDirectories(mods.resolve("missing"));
            Files.writeString(missing.resolve("mod.json"),mod("missing",",\"documentation\":\"doc\"",
                ",\"resources\":{\"doc\":{\"kind\":\"file\",\"path\":\"README.md\"}}"));
            Path escape=Files.createDirectories(mods.resolve("escape"));
            Files.writeString(escape.resolve("mod.json"),mod("escape","",
                ",\"resources\":{\"asset\":{\"kind\":\"file\",\"path\":\"../outside.txt\"}}"));
            Path broken=Files.createDirectories(mods.resolve("broken"));Files.writeString(broken.resolve("mod.json"),"{not json}");
            Files.createDirectories(mods.resolve("no-manifest"));
            PackageInventory.Snapshot snapshot=PackageInventory.inspect(mods);
            require(snapshot.entries().size()==5,"all package directories visible");
            require(snapshot.find("okay").loadable(),"valid v2 stays loadable");
            require(snapshot.find("okay").documentation!=null,"optional Markdown reference resolved");
            require(snapshot.find("okay").tags.size()==1,"tag normalization/dedup");
            require(snapshot.find("missing").loadable(),"optional missing README is nonblocking");
            require(snapshot.find("missing").issues.stream().anyMatch(x->x.code().equals("MISSING_DOCUMENTATION")),"missing README warning");
            require(!snapshot.find("escape").loadable(),"path escape blocked");
            require(snapshot.issues().stream().anyMatch(x->x.code().equals("MISSING_MANIFEST")),"manifestless package diagnosed");
            String injected="# Hi\n<script>evil</script>\n![tracking](https://tracker.invalid/track.png)\n"+
                "![unsafe](../outside.png)\n[bad](javascript:alert)\n[good](https://example.org)\n";
            String html=SafeMarkdown.toHtml(injected);
            require(!html.contains("<script>")&&!html.contains("tracker.invalid")&&!html.contains("<img")&&!html.contains("href=\"javascript:"),"script/URI sanitation");
            require(html.contains("&lt;script&gt;")&&html.contains("href=\"https://example.org\""),"safe markup/links");
            // README images: shown only from inside the mod's folder, as PNG, JPEG or GIF; never fetched.
            Path pics=Files.createDirectories(okay.resolve("images"));
            java.awt.image.BufferedImage px=new java.awt.image.BufferedImage(1600,400,java.awt.image.BufferedImage.TYPE_INT_RGB);
            javax.imageio.ImageIO.write(px,"png",pics.resolve("wide.png").toFile());
            javax.imageio.ImageIO.write(px,"png",root.resolve("outside.png").toFile());
            Files.writeString(pics.resolve("fake.png"),"not an image");
            SafeMarkdown.Images own=new SafeMarkdown.Images(okay,okay,2.0);
            String shown=SafeMarkdown.toHtml("![Wide](images/wide.png)\n",own);
            require(shown.contains("<img src=\"file:")&&shown.contains("width=\"756\" height=\"189\""),"local image shown, scaled to the column");
            require(SafeMarkdown.toHtml("![Wide](images/wide.png)\n",new SafeMarkdown.Images(okay,okay,4.0)).contains("width=\"400\" height=\"100\""),"image never upscaled past its pixels");
            for(String bad:List.of("../outside.png","images/../../outside.png",root.resolve("outside.png").toString(),"file:///"+root.resolve("outside.png"),
                    "https://tracker.invalid/x.png","images/fake.png","images/missing.png","images\\wide.png","/images/wide.png"))
                require(!SafeMarkdown.toHtml("![Alt]("+bad+")\n",own).contains("<img"),"image refused: "+bad);
            Path dup=Files.createDirectories(mods.resolve("duplicate"));Files.writeString(dup.resolve("mod.json"),mod("okay","",""));
            snapshot=PackageInventory.inspect(mods);
            require(!snapshot.find("okay").loadable(),"duplicate identity blocks every matching entry");
            System.out.println("STUDIO_INVENTORY_OK discovery=6 valid_tags=ok optional_docs=ok path_security=ok malformed=ok duplicate=ok markdown_safety=ok readme_images=ok");
        } finally {
            try(var stream=Files.walk(root)){for(Path p:stream.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
}
