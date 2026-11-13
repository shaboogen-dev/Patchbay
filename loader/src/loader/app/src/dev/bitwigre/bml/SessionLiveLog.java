package dev.bitwigre.bml;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Non-blocking caller-owned polling cursor; invoked only off the Swing EDT. */
final class SessionLiveLog {
    private static final int MAX_READ=65536;
    private final Path file;
    private long offset;
    SessionLiveLog(Path folder) throws IOException {
        if(Files.isSymbolicLink(folder) || !Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe session log directory");
        file=folder.resolve("bml.log");
    }
    record Delta(String text,long nextOffset,boolean reset,boolean more){}
    synchronized Delta read() throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))
            return new Delta("",offset,false,false);
        long length=Files.size(file);
        boolean reset=length<offset;
        if(reset)offset=0;
        // Avoid unbounded initial log reads: show a marker if starting late.
        if(offset==0&&length>1024*1024){offset=Math.max(0,length-1024*1024);reset=true;}
        int lengthToRead=(int)Math.min(MAX_READ,length-offset);
        if(lengthToRead<=0)return new Delta("",offset,reset,false);
        byte[] bytes=new byte[lengthToRead];
        try(RandomAccessFile in=new RandomAccessFile(file.toFile(),"r")){
            in.seek(offset);in.readFully(bytes);
        }
        offset+=lengthToRead;
        return new Delta(new String(bytes,StandardCharsets.UTF_8),offset,reset,length>offset);
    }
}
