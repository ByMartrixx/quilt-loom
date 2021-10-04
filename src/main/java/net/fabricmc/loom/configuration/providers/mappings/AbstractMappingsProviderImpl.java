package net.fabricmc.loom.configuration.providers.mappings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.function.Consumer;

import org.apache.commons.io.FileUtils;
import org.apache.tools.ant.util.StringUtils;
import org.gradle.api.Project;
import org.zeroturnaround.zip.FileSource;
import org.zeroturnaround.zip.ZipEntrySource;
import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.mapping.reader.v2.TinyV2Factory;
import net.fabricmc.mapping.tree.TinyTree;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.tinyv2.CommandMergeTinyV2;
import net.fabricmc.stitch.commands.tinyv2.CommandReorderTinyV2;

public abstract class AbstractMappingsProviderImpl extends DependencyProvider implements MappingsProvider {
	public String minecraftVersion;
	public String mappingsName;
	public String mappingsVersion;

	protected final Path mappingsDir;
	protected final Path mappingsStepsDir;
	// The mappings that gradle gives us
	protected Path baseTinyMappings;
	// The mappings we use in practice
	public File tinyMappings;
	public File tinyMappingsJar;

	public AbstractMappingsProviderImpl(Project project) {
		super(project);
		mappingsDir = getDirectories().getUserCache().toPath().resolve(Constants.Mappings.MAPPINGS_CACHE_DIR);
		mappingsStepsDir = mappingsDir.resolve(Constants.Mappings.MAPPINGS_STEPS_CACHE_DIR);
	}

	public void clean() throws IOException {
		FileUtils.deleteDirectory(mappingsDir.toFile());
	}

	public TinyTree getMappings() throws IOException {
		return MappingsCache.INSTANCE.get(tinyMappings.toPath());
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProviderImpl minecraftProvider = getDependencyManager().getProvider(MinecraftProviderImpl.class);

		getProject().getLogger().info(":setting up mappings (" + dependency.getDependency().getName() + " " + dependency.getResolvedVersion() + ")");

		String version = dependency.getResolvedVersion();
		File mappingsJar = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not find mappings: " + dependency));

		this.minecraftVersion = minecraftProvider.minecraftVersion();
		this.mappingsName = StringUtils.removeSuffix(dependency.getDependency().getGroup() + "." + dependency.getDependency().getName(), "-unmerged");

		boolean isV2 = doesJarContainV2Mappings(mappingsJar.toPath());
		this.mappingsVersion = version + (isV2 ? "-v2" : "");

		initFiles();

		if (isRefreshDeps()) {
			cleanFiles();
		}

		Files.createDirectories(mappingsDir);
		Files.createDirectories(mappingsStepsDir);

		String[] depStringSplit = dependency.getDepString().split(":");
		String jarClassifier = "final";

		if (depStringSplit.length >= 4) {
			jarClassifier = jarClassifier + depStringSplit[3];
		}

		tinyMappings = mappingsDir.resolve(StringUtils.removeSuffix(mappingsJar.getName(), ".jar") + ".tiny").toFile();
		tinyMappingsJar = new File(getDirectories().getUserCache(), mappingsJar.getName().replace(".jar", "-" + jarClassifier + ".jar"));

		if (!tinyMappings.exists() || isRefreshDeps()) {
			storeMappings(getProject(), minecraftProvider, mappingsJar.toPath());
		}

		if (!tinyMappingsJar.exists() || isRefreshDeps()) {
			ZipUtil.pack(new ZipEntrySource[] {new FileSource(Constants.Mappings.MAPPINGS_FILE_PATH, tinyMappings)}, tinyMappingsJar);
		}

		addDependency(tinyMappingsJar, Constants.Configurations.MAPPINGS_FINAL);
	}

	protected void storeMappings(Project project, MinecraftProviderImpl minecraftProvider, Path mappingsJar) throws IOException {
		project.getLogger().info(":extracting " + mappingsJar.getFileName());

		try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsJar, (ClassLoader) null)) {
			extractMappings(fileSystem, baseTinyMappings);
		}

		if (baseMappingsAreV2()) {
			// These are unmerged v2 mappings
			mergeAndSaveMappings(project, mappingsJar);
		} else {
			// These are merged v1 mappings
			if (tinyMappings.exists()) {
				tinyMappings.delete();
			}
		}
	}

	protected final boolean baseMappingsAreV2() throws IOException {
		return areMappingsV2(baseTinyMappings);
	}

	protected final boolean doesJarContainV2Mappings(Path path) throws IOException {
		try (FileSystem fs = FileSystems.newFileSystem(path, (ClassLoader) null)) {
			return areMappingsV2(getMappingsFilePath(fs));
		}
	}

	protected final boolean areMappingsV2(Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path)) {
			TinyV2Factory.readMetadata(reader);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	protected static Path getMappingsFilePath(FileSystem jar) {
		Path path = jar.getPath(Constants.Mappings.MAPPINGS_FILE_PATH);
		if (!Files.exists(path)) {
			path = jar.getPath(Constants.Mappings.FABRIC_MAPPINGS_FILE_PATH);
		}

		return path;
	}

	public static void extractMappings(FileSystem jar, Path extractTo) throws IOException {
		Files.copy(getMappingsFilePath(jar), extractTo, StandardCopyOption.REPLACE_EXISTING);
	}

	protected abstract void mergeAndSaveMappings(Project project, Path unmergedMappingsJar) throws IOException;

	protected void reorderMappings(Path oldMappings, Path newMappings, String... newOrder) {
		System.out.println("Reordering " + oldMappings.toAbsolutePath() + " to " + Arrays.toString(newOrder) + " and saving to " + newMappings.toAbsolutePath());
		Command command = new CommandReorderTinyV2();
		String[] args = new String[2 + newOrder.length];
		args[0] = oldMappings.toAbsolutePath().toString();
		args[1] = newMappings.toAbsolutePath().toString();
		System.arraycopy(newOrder, 0, args, 2, newOrder.length);
		runCommand(command, args);
	}

	protected void mergeMappings(Path hashedMappings, Path mappings, Path newMergedMappings) {
		try {
			Command command = new CommandMergeTinyV2();
			runCommand(command, hashedMappings.toAbsolutePath().toString(),
					mappings.toAbsolutePath().toString(),
					newMergedMappings.toAbsolutePath().toString());
		} catch (Exception e) {
			throw new RuntimeException("Could not merge mappings from " + hashedMappings.toString()
					+ " with mappings from " + mappings, e);
		}
	}

	protected void runCommand(Command command, String... args) {
		try {
			command.run(args);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	protected void initFiles() {
		baseTinyMappings = mappingsDir.resolve(mappingsName + "-" + mappingsVersion + "-" + minecraftVersion + "-base.tiny");
	}

	@Override
	public File getTinyMappings() {
		return this.tinyMappings;
	}

	public void cleanFiles() {
		try {
			if (Files.exists(mappingsStepsDir)) {
				Files.walkFileTree(mappingsStepsDir, new DeletingFileVisitor());
			}

			if (Files.exists(baseTinyMappings)) {
				Files.deleteIfExists(baseTinyMappings);
			}

			if (tinyMappings != null) {
				tinyMappings.delete();
			}

			if (tinyMappingsJar != null) {
				tinyMappingsJar.delete();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public Path getMappingsDir() {
		return mappingsDir;
	}

	public boolean hasUnpickDefinitions() {
		return false;
	}
}
