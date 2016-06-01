package jbyoshi.gradlegrab;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.FileSystem;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.stream.*;

public final class GradleGrab {
	private static final String VERSION_FILE = "version";
	private static final String INSTALL_IN_PROGRESS_FILE = "installInProgress";

	public static void main(String[] args) {
		boolean offline = false;
		Path gradleHome = Paths.get(System.getProperty("user.home"), ".gradle");
		for (String arg : args) {
			// All arguments should be built in to Gradle.
			if (gradleHome == null) {
				gradleHome = Paths.get(arg);
			} else if (arg.equals("--offline")) {
				offline = true;
			} else if (arg.equals("-g") || arg.equals("--gradle-user-home")) {
				gradleHome = null;
			}
		}
		if (gradleHome == null) {
			System.err.println("Invalid Gradle home specified.");
			System.exit(1);
			return; // Shuts up Eclipse's "gradleHome may be null"
		}
		Path grabDir = gradleHome.resolve("grab");
		Path versionFile = grabDir.resolve(VERSION_FILE);
		String version;
		Path gradleDir;
		if (!offline) {
			try {
				String json;
				try (BufferedReader in = new BufferedReader(
						new InputStreamReader(new URL("https://services.gradle.org/versions/current").openStream()));
						StringWriter out = new StringWriter()) {
					// At the time of writing, this is more than enough (the
					// JSON is 244 characters long).
					char[] cbuf = new char[300];
					int len;
					while ((len = in.read(cbuf)) > 0) {
						out.write(cbuf, 0, len);
					}
					json = out.toString();
				}

				version = getJsonValue(json, "version");
				gradleDir = grabDir.resolve("gradle-" + version);
				if (!Files.isRegularFile(versionFile)) {
					Files.createFile(versionFile);
				}
				try (FileChannel channel = FileChannel.open(versionFile, StandardOpenOption.WRITE);
						Writer out = new OutputStreamWriter(Channels.newOutputStream(channel));
						FileLock lock = channel.lock()) {
					Path installInProgress = grabDir.resolve(INSTALL_IN_PROGRESS_FILE);
					if (!Files.isDirectory(gradleDir) || Files.exists(installInProgress)) {
						System.out.println("Updating to Gradle " + version);
						Path download = java.io.File.createTempFile("gradle-" + version + "-download", ".zip").toPath();
						download(new URL(getJsonValue(json, "downloadUrl")), download);
						if (!Files.exists(installInProgress)) {
							Files.createFile(installInProgress);
						}
						extract(download, grabDir);
						Path gradleScript = gradleDir.resolve("bin/gradle");
						try {
							Set<PosixFilePermission> perms = Files.getPosixFilePermissions(gradleScript);
							if (perms.contains(PosixFilePermission.OWNER_READ)) {
								perms.add(PosixFilePermission.OWNER_EXECUTE);
							}
							Files.setPosixFilePermissions(gradleScript, perms);
						} catch (UnsupportedOperationException e) {
							// Not on Unix.
						}
						Files.deleteIfExists(installInProgress);
						System.out.println("Update completed.");
					}
					out.append(version);
					out.flush();
				}
			} catch (IOException e) {
				System.err.print("Unable to update Gradle installation: ");
				e.printStackTrace();
				System.exit(1);
				return;
			}
		} else if (!Files.isReadable(versionFile)) {
			System.err.println("You must run Gradle once in online mode to download the files.");
			System.err.println("Please remove --offline from your script arguments.");
			System.exit(1);
			return;
		} else {
			try {
				version = String.join(" ", Files.readAllLines(versionFile));
				gradleDir = grabDir.resolve("gradle-" + version);
				if (!Files.isDirectory(gradleDir)) {
					System.err.println("You must run Gradle once in online mode to download the files.");
					System.err.println("Please remove --offline from your script arguments.");
					System.exit(1);
				}
			} catch (IOException e) {
				System.err.print("Unable to launch Gradle: ");
				e.printStackTrace();
				System.exit(1);
				return;
			}
		}

		List<String> gradleCmd = new ArrayList<>(args.length + 3);
		if (System.getProperty("os.name").toLowerCase().contains("win")) {
			gradleCmd.add("cmd");
			gradleCmd.add("/c");
			gradleCmd.add(gradleDir.resolve("bin/gradle.bat").toAbsolutePath().toString());
		} else {
			gradleCmd.add(gradleDir.resolve("bin/gradle").toAbsolutePath().toString());
		}
		gradleCmd.addAll(Arrays.asList(args));
		ProcessBuilder builder = new ProcessBuilder(gradleCmd);
		builder.directory(Paths.get("").toFile());
		builder.inheritIO();

		Process process;
		try {
			process = builder.start();
		} catch (IOException e) {
			throw new AssertionError("Unable to launch Gradle", e);
		}
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (process.isAlive()) {
				process.destroy();
			}
		}, "Gradle Shutdown"));
		try {
			System.exit(process.waitFor());
		} catch (InterruptedException e) {
			// This is **NOT** a SIGINT or SIGTERM. This is a
			// Thread.interrupt().
			System.err.println(GradleGrab.class.getSimpleName() + " got InterruptedException");
		}
	}

	private static void extract(Path zip, Path destDir) throws IOException {
		System.out.println("Extracting files...");
		URI uri = URI.create("jar:" + zip.toUri().toString() + "!/");
		try (FileSystem zipFile = FileSystems.newFileSystem(uri, new HashMap<>())) {
			Path zipRoot = Paths.get(uri);
			try (Stream<Path> walk = Files.walk(zipRoot)) {
				for (Path src : (Iterable<Path>) walk::iterator) {
					Path dest = destDir.resolve(zipRoot.relativize(src).toString());
					if (Files.isDirectory(src)) {
						if (!Files.isDirectory(dest)) {
							Files.createDirectories(dest);
						}
					} else {
						Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		}
	}

	private static void download(URL url, Path dest) throws IOException {
		Files.createDirectories(dest.getParent());

		System.out.println("Downloading " + url);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
				BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(dest))) {
			byte[] buf = new byte[10000];
			long read = 0, total = conn.getContentLengthLong();
			long updateTime = System.currentTimeMillis();
			int readCurrentPass;
			while ((readCurrentPass = in.read(buf)) > 0) {
				if (Thread.currentThread().isInterrupted()) {
					throw new IOException("Download was interrupted");
				}
				out.write(buf, 0, readCurrentPass);
				read += readCurrentPass;
				if (updateTime + 1000 < System.currentTimeMillis()) {
					System.out.println(
							formatFileSize(read) + " / " + formatFileSize(total) + " (" + read * 100 / total + "%)");
					updateTime = System.currentTimeMillis();
				}
			}
			out.flush();
		}
	}

	private static String formatFileSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			double kb = (double) bytes / 1024;
			return String.format("%.2f KB", kb);
		}
		if (bytes < 1024 * 1024 * 1024) {
			double mb = (double) bytes / 1024 / 1024;
			return String.format("%.2f MB", mb);
		}
		double gb = (double) bytes / 1024 / 1024 / 1024;
		return String.format("%.2f GB", gb);
	}

	private static String getJsonValue(String json, String key) {
		String escapedKey = "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		int index = 0;
		while (true) {
			index = json.indexOf(escapedKey, index) + escapedKey.length();
			if (index < 0) {
				throw new RuntimeException("Key " + key + " not found in JSON " + json);
			}
			while (Character.isWhitespace(json.charAt(index))) {
				index++;
			}
			if (json.charAt(index) == ':') {
				break;
			}
		}

		index++;
		while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
			index++;
		}
		if (index == json.length()) {
			throw new RuntimeException("Reached end of string while finding value of key " + key + " in JSON " + json);
		}

		if (json.charAt(index) != '"') {
			throw new RuntimeException("Key " + key + " was not a string - index " + index + " in JSON " + json);
		}
		StringBuilder sb = new StringBuilder(50);
		for (index++; json.charAt(index) != '"'; index++) {
			char c = json.charAt(index);
			if (c == '\\') {
				index++;
				switch (json.charAt(index)) {
				case '\\':
				case '"':
				case '\'':
				case '/':
					c = json.charAt(index);
					break;
				case 'n':
					c = '\n';
					break;
				case 't':
					c = '\t';
					break;
				default:
					throw new RuntimeException("Unknown escape character \\" + json.charAt(index) + "at index "
							+ (index - 1) + " in JSON " + json);
				}
			}
			sb.append(c);
		}
		return sb.toString();
	}
}
