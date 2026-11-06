package lk.aak.agency.intake;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/** A PDF held in memory, so it can be stored by the same code that stores an uploaded invoice file. */
class ByteArrayMultipartFile implements MultipartFile {

    private final String name;
    private final byte[] content;

    ByteArrayMultipartFile(String name, byte[] content) {
        this.name = name;
        this.content = content;
    }

    @Override public String getName() { return "file"; }
    @Override public String getOriginalFilename() { return name; }
    @Override public String getContentType() { return "application/pdf"; }
    @Override public boolean isEmpty() { return content.length == 0; }
    @Override public long getSize() { return content.length; }
    @Override public byte[] getBytes() { return content; }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
    @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), content); }
}
