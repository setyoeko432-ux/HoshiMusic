package org.gradle.wrapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Small self-contained Gradle bootstrapper used only to keep this generated
 * project portable. It downloads the distribution declared in
 * gradle-wrapper.properties and forwards all command-line arguments to Gradle.
 */
public final class GradleWrapperMain {
    private GradleWrapperMain() {}

    public static void main(String[] args) throws Exception {
        URI codeSource = GradleWrapperMain.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI();
        File wrapperJar = new File(codeSource);
        File projectDir = wrapperJar.getParentFile().getParentFile().getParentFile();
        File propertiesFile = new File(projectDir, "gradle/wrapper/gradle-wrapper.properties");

        Properties properties = new Properties();
        InputStream propertiesInput = new FileInputStream(propertiesFile);
        try {
            properties.load(propertiesInput);
        } finally {
            propertiesInput.close();
        }

        String distributionUrl = properties.getProperty("distributionUrl");
        if (distributionUrl == null || distributionUrl.trim().isEmpty()) {
            throw new IllegalStateException("distributionUrl tidak ditemukan");
        }

        String archiveName = distributionUrl.substring(distributionUrl.lastIndexOf('/') + 1);
        String cacheName = archiveName.replaceAll("[^A-Za-z0-9._-]", "_");
        File cacheDir = new File(System.getProperty("user.home"),
                ".gradle/wrapper/dists/hoshimusic/" + cacheName);
        File readyMarker = new File(cacheDir, ".ready");

        if (!readyMarker.isFile()) {
            installDistribution(new URL(distributionUrl), cacheDir, archiveName);
            if (!readyMarker.createNewFile() && !readyMarker.isFile()) {
                throw new IOException("Gagal membuat penanda distribusi Gradle");
            }
        }

        File gradleExecutable = findGradleExecutable(cacheDir);
        if (gradleExecutable == null) {
            deleteRecursively(cacheDir);
            throw new IOException("Executable Gradle tidak ditemukan; jalankan kembali gradlew");
        }
        if (!isWindows()) {
            gradleExecutable.setExecutable(true);
        }

        List<String> command = new ArrayList<String>();
        if (isWindows()) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(gradleExecutable.getAbsolutePath());
        for (String arg : args) {
            command.add(arg);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectDir);
        processBuilder.inheritIO();
        Process process = processBuilder.start();
        System.exit(process.waitFor());
    }

    private static void installDistribution(URL url, File cacheDir, String archiveName)
            throws IOException {
        deleteRecursively(cacheDir);
        if (!cacheDir.mkdirs() && !cacheDir.isDirectory()) {
            throw new IOException("Gagal membuat cache Gradle: " + cacheDir);
        }

        File partial = new File(cacheDir, archiveName + ".part");
        File archive = new File(cacheDir, archiveName);
        System.out.println("Mengunduh " + url + " ...");
        download(url, partial, 0);
        Files.move(partial.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
        unzip(archive, cacheDir);
        if (!archive.delete()) {
            archive.deleteOnExit();
        }
    }

    private static void download(URL url, File destination, int redirects) throws IOException {
        if (redirects > 8) {
            throw new IOException("Terlalu banyak redirect saat mengunduh Gradle");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "HoshiMusic-GradleBootstrap/1.0");
        connection.setInstanceFollowRedirects(false);
        int status = connection.getResponseCode();
        if (status >= 300 && status < 400) {
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) {
                throw new IOException("Redirect tanpa lokasi");
            }
            download(new URL(url, location), destination, redirects + 1);
            return;
        }
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("Unduhan Gradle gagal dengan HTTP " + status);
        }

        InputStream input = new BufferedInputStream(connection.getInputStream());
        BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination));
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        } finally {
            try { output.close(); } finally { input.close(); }
            connection.disconnect();
        }
    }

    private static void unzip(File archive, File destination) throws IOException {
        String destinationPath = destination.getCanonicalPath() + File.separator;
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)));
        try {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                File output = new File(destination, entry.getName());
                String outputPath = output.getCanonicalPath();
                if (!outputPath.startsWith(destinationPath)) {
                    throw new IOException("Entri ZIP tidak aman: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) {
                        throw new IOException("Gagal membuat direktori: " + output);
                    }
                } else {
                    File parent = output.getParentFile();
                    if (!parent.mkdirs() && !parent.isDirectory()) {
                        throw new IOException("Gagal membuat direktori: " + parent);
                    }
                    BufferedOutputStream fileOutput = new BufferedOutputStream(new FileOutputStream(output));
                    try {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            fileOutput.write(buffer, 0, count);
                        }
                    } finally {
                        fileOutput.close();
                    }
                }
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
    }

    private static File findGradleExecutable(File directory) {
        File[] children = directory.listFiles();
        if (children == null) return null;
        String target = isWindows() ? "gradle.bat" : "gradle";
        for (File child : children) {
            if (child.isDirectory()) {
                File bin = new File(child, "bin" + File.separator + target);
                if (bin.isFile()) return bin;
                File nested = findGradleExecutable(child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IOException("Gagal menghapus " + file);
        }
    }
}
