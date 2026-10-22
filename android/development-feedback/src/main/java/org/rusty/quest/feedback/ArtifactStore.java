package org.rusty.quest.feedback;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Immutable private generation staging. The caller supplies an app-private directory and trusted digest. */
public final class ArtifactStore {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final String RESIDENT_PREFIX = "org/rusty/quest/feedback/";
    private ArtifactStore() { }

    public static final class Artifact {
        private final File file;
        private final String id, sha256;
        private final Set<String> definitions;
        private Artifact(File file, String id, String sha256, Set<String> definitions) {
            this.file=file; this.id=id; this.sha256=sha256;
            this.definitions=Collections.unmodifiableSet(new HashSet<>(definitions));
        }
        public File file() { return file; }
        public String generationId() { return id; }
        public String sha256() { return sha256; }
        public boolean defines(String binaryName) { return definitions.contains(binaryName); }
        public void verify() throws IOException {
            if (file.canWrite()) throw new IllegalArgumentException("Artifact is writable");
            if (!sha256.equals(digest(file))) throw new IllegalArgumentException("Artifact digest changed");
        }
    }

    /** Opens new file -> read-only -> writes open descriptor -> closes -> verifies digest and definitions. */
    public static Artifact stage(File directory, String id, InputStream content, String sha256, boolean dex) throws IOException {
        if (id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid generation identity");
        if (sha256==null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
        Files.createDirectories(directory.toPath());
        Path path=directory.toPath().resolve(id+"-"+UUID.randomUUID()+".jar");
        File file=path.toFile();
        boolean complete=false;
        try {
            try (OutputStream out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                 InputStream source=Objects.requireNonNull(content,"content")) {
                if (!file.setReadOnly()) throw new IOException("Cannot mark artifact read-only before writing");
                byte[] chunk=new byte[8192]; int count,total=0;
                while ((count=source.read(chunk))!=-1) {
                    total+=count; if(total>MAX_BYTES)throw new IllegalArgumentException("Artifact too large");
                    out.write(chunk,0,count);
                }
            }
            if (!sha256.equals(digest(file))) throw new IllegalArgumentException("Artifact digest mismatch");
            Set<String> definitions=definitions(file,dex);
            Artifact artifact=new Artifact(file,id,sha256,definitions);
            complete=true;
            return artifact;
        } finally {
            if (!complete && file.exists()) {
                // This exact newly-created candidate only; accepted generations are never overwritten.
                if (!file.setWritable(true)) throw new IOException("Cannot remove rejected candidate");
                Files.delete(path);
            }
        }
    }

    private static String digest(File file) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(file)) {
                byte[] chunk=new byte[8192];int count;
                while((count=in.read(chunk))!=-1)digest.update(chunk,0,count);
            }
            StringBuilder text=new StringBuilder();
            for(byte b:digest.digest())text.append(String.format(Locale.ROOT,"%02x",b&255));
            return text.toString();
        } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static Set<String> definitions(File file, boolean dex) throws IOException {
        Set<String> result=new HashSet<>(), entries=new HashSet<>();int expanded=0;boolean found=false;
        try(ZipFile zip=new ZipFile(file)) {
            Enumeration<? extends ZipEntry> all=zip.entries();
            while(all.hasMoreElements()) {
                ZipEntry entry=all.nextElement();String name=entry.getName();
                if (!entries.add(name)) throw new IllegalArgumentException("Duplicate archive entry");
                if(entry.isDirectory())continue;
                if(name.endsWith(".class")) {
                    if(dex)throw new IllegalArgumentException("Mixed class/DEX artifact");
                    addDefinition(result,name.substring(0,name.length()-6));found=true;
                } else if(name.endsWith(".dex")) {
                    if(!dex || !name.equals("classes.dex"))throw new IllegalArgumentException("Only one classes.dex is supported");
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    try(InputStream in=zip.getInputStream(entry)) {
                        byte[] chunk=new byte[8192];int count;
                        while((count=in.read(chunk))!=-1) {
                            expanded+=count;if(expanded>MAX_BYTES)throw new IllegalArgumentException("Expanded DEX too large");
                            bytes.write(chunk,0,count);
                        }
                    }
                    for(String descriptor:DexDefinitions.read(bytes.toByteArray())) {
                        addDefinition(result,descriptor.substring(1,descriptor.length()-1));
                    }
                    found=true;
                } else throw new IllegalArgumentException("Unexpected plugin payload (manifests/dependency paths are forbidden): "+name);
            }
        } catch(ZipException invalid) { throw new IllegalArgumentException("Malformed plugin archive",invalid); }
        if(!found || result.isEmpty())throw new IllegalArgumentException("No implementation definitions");
        return result;
    }
    private static void addDefinition(Set<String> names,String internalName) {
        if(internalName.startsWith(RESIDENT_PREFIX))throw new IllegalArgumentException("Resident contract/host namespace packaged in plugin");
        if(!internalName.matches("[A-Za-z_$][A-Za-z0-9_$/]*") || !names.add(internalName.replace('/','.')))
            throw new IllegalArgumentException("Invalid or duplicate class definition");
    }
}
