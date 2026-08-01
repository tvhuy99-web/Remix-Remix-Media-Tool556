package org.gradle.wrapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Small, dependency-free Gradle wrapper bootstrapper.
 * It intentionally implements only what this single-project handoff needs.
 */
public final class GradleWrapperMain {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private GradleWrapperMain() {}

    public static void main(String[] args) throws Exception {
        File projectDir = locateProjectDir();
        Properties props = loadProperties(projectDir);
        String distributionUrl = required(props, "distributionUrl").replace("\\:", ":");
        String expectedSha = required(props, "distributionSha256Sum").toLowerCase(Locale.ROOT);

        File gradleUserHome = new File(System.getenv().containsKey("GRADLE_USER_HOME")
                ? System.getenv("GRADLE_USER_HOME")
                : new File(System.getProperty("user.home"), ".gradle").getAbsolutePath());
        File distsDir = new File(gradleUserHome, "wrapper/dists/mediatool");
        if (!distsDir.isDirectory() && !distsDir.mkdirs()) {
            throw new IOException("Cannot create Gradle cache directory: " + distsDir);
        }

        String zipName = new File(new URI(distributionUrl).getPath()).getName();
        String versionName = zipName.replace("-bin.zip", "").replace("-all.zip", "");
        File installDir = new File(distsDir, versionName + "-" + shortHash(distributionUrl));
        File marker = new File(installDir, ".installed");
        File lockFile = new File(distsDir, installDir.getName() + ".lock");

        try (FileChannel channel = new FileOutputStream(lockFile, true).getChannel();
             FileLock ignored = channel.lock()) {
            if (!marker.isFile()) {
                installDistribution(distributionUrl, expectedSha, installDir);
                if (!marker.createNewFile()) {
                    throw new IOException("Cannot create install marker: " + marker);
                }
            }
        }

        File gradleHome = findGradleHome(installDir);
        File executable = new File(gradleHome, isWindows() ? "bin/gradle.bat" : "bin/gradle");
        if (!executable.isFile()) {
            throw new IOException("Gradle executable not found: " + executable);
        }
        if (!isWindows()) {
            executable.setExecutable(true);
        }

        List<String> command = new ArrayList<String>();
        command.add(executable.getAbsolutePath());
        for (String arg : args) command.add(arg);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectDir);
        processBuilder.inheritIO();
        int exitCode = processBuilder.start().waitFor();
        System.exit(exitCode);
    }

    private static File locateProjectDir() throws Exception {
        URL location = GradleWrapperMain.class.getProtectionDomain().getCodeSource().getLocation();
        File jar = new File(location.toURI());
        File current = jar.getParentFile();
        while (current != null) {
            if (new File(current, "settings.gradle.kts").isFile()
                    || new File(current, "settings.gradle").isFile()) {
                return current.getCanonicalFile();
            }
            current = current.getParentFile();
        }
        return new File(System.getProperty("user.dir")).getCanonicalFile();
    }

    private static Properties loadProperties(File projectDir) throws IOException {
        File file = new File(projectDir, "gradle/wrapper/gradle-wrapper.properties");
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(file)) {
            props.load(input);
        }
        return props;
    }

    private static String required(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing " + key + " in gradle-wrapper.properties");
        }
        return value.trim();
    }

    private static void installDistribution(String distributionUrl, String expectedSha, File installDir) throws Exception {
        File parent = installDir.getParentFile();
        File partialZip = new File(parent, installDir.getName() + ".zip.part");
        File finalZip = new File(parent, installDir.getName() + ".zip");
        File tempDir = new File(parent, installDir.getName() + ".tmp");

        deleteRecursively(tempDir.toPath());
        deleteRecursively(installDir.toPath());
        if (!tempDir.mkdirs()) throw new IOException("Cannot create temporary directory: " + tempDir);

        if (!finalZip.isFile() || !sha256(finalZip).equals(expectedSha)) {
            Files.deleteIfExists(partialZip.toPath());
            download(distributionUrl, partialZip);
            String actualSha = sha256(partialZip);
            if (!actualSha.equals(expectedSha)) {
                Files.deleteIfExists(partialZip.toPath());
                throw new SecurityException("Gradle distribution checksum mismatch. Expected "
                        + expectedSha + " but got " + actualSha);
            }
            Files.move(partialZip.toPath(), finalZip.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        unzipSafely(finalZip, tempDir);
        try {
            Files.move(tempDir.toPath(), installDir.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempDir.toPath(), installDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void download(String source, File destination) throws Exception {
        URL url = new URL(source);
        for (int redirects = 0; redirects < 8; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "MediaTool-Gradle-Wrapper");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Redirect without Location header");
                url = new URL(url, location);
                continue;
            }
            if (code < 200 || code >= 300) {
                throw new IOException("Gradle download failed with HTTP " + code + " from " + url);
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                copy(input, output);
            } finally {
                connection.disconnect();
            }
            return;
        }
        throw new IOException("Too many redirects while downloading Gradle");
    }

    private static void unzipSafely(File zip, File targetDir) throws IOException {
        String targetRoot = targetDir.getCanonicalPath() + File.separator;
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = input.getNextEntry()) != null) {
                File output = new File(targetDir, entry.getName());
                String outputPath = output.getCanonicalPath();
                if (!outputPath.startsWith(targetRoot)) {
                    throw new SecurityException("Unsafe ZIP entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!output.isDirectory() && !output.mkdirs()) {
                        throw new IOException("Cannot create directory: " + output);
                    }
                } else {
                    File parent = output.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Cannot create directory: " + parent);
                    }
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                        int read;
                        while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
                    }
                }
                input.closeEntry();
            }
        }
    }

    private static File findGradleHome(File installDir) throws IOException {
        File[] children = installDir.listFiles();
        if (children == null) throw new IOException("Cannot read Gradle install directory: " + installDir);
        for (File child : children) {
            if (child.isDirectory() && new File(child, "bin").isDirectory()) return child;
        }
        if (new File(installDir, "bin").isDirectory()) return installDir;
        throw new IOException("Gradle home directory not found under " + installDir);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format("%02x", b));
        return result.toString();
    }

    private static String shortHash(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes("UTF-8"));
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 8; i++) result.append(String.format("%02x", bytes[i]));
        return result.toString();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walk(path)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException e) { throw new RuntimeException(e); }
                });
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
