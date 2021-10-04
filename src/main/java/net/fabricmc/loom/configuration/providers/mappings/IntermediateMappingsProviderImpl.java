package net.fabricmc.loom.configuration.providers.mappings;

import java.io.BufferedReader;
import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.tools.ant.util.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.zeroturnaround.zip.FileSource;
import org.zeroturnaround.zip.ZipEntrySource;
import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.mapping.reader.v2.TinyMetadata;
import net.fabricmc.mapping.reader.v2.TinyV2Factory;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.tinyv2.CommandMergeTinyV2;

public class IntermediateMappingsProviderImpl extends AbstractMappingsProviderImpl {
	private final List<MappingsDependency> mappingsDependencies = new ArrayList<>();
	private List<String> tinyMappingsNamespaces;

	public IntermediateMappingsProviderImpl(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependencyInfo, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProviderImpl minecraftProvider = getDependencyManager().getProvider(MinecraftProviderImpl.class);

		Dependency dependency = dependencyInfo.getDependency();
		String group = dependency.getGroup();
		String name = dependency.getName();
		String version = dependencyInfo.getResolvedVersion();
		String depString = dependencyInfo.getDepString();

		getProject().getLogger().info(":setting up intermediate mappings (" + name + " " + version + ")");

		File mappingsJar = dependencyInfo.resolveFile().orElseThrow(() -> new RuntimeException("Could not find mappings: " + dependencyInfo));

		if (this.minecraftVersion == null) {
			this.minecraftVersion = minecraftProvider.minecraftVersion();
		}

		String baseName = StringUtils.removeSuffix(group + "." + name, "-unmerged");
		Path baseMappings = this.mappingsDir.resolve(baseName + "-" + version + "-" + this.minecraftVersion + "-base.tiny");
		MappingsDependency mappingsDependency = new MappingsDependency(mappingsJar, baseMappings, group, name, version, depString);
		this.mappingsDependencies.add(mappingsDependency);
	}

	public void endProcessing() throws Exception {
		initFiles();

		if (isRefreshDeps()) {
			cleanFiles();
		}

		Files.createDirectories(this.mappingsDir);
		Files.createDirectories(this.mappingsStepsDir);

		String tinyMappingsFilename = this.mappingsDependencies.stream().map(MappingsDependency::getBaseName).collect(Collectors.joining("_"));

		this.tinyMappings = mappingsDir.resolve(tinyMappingsFilename + ".tiny").toFile();
		this.tinyMappingsJar = new File(getDirectories().getUserCache(), tinyMappingsFilename + "-final.jar");

		if (!this.tinyMappings.exists() || isRefreshDeps()) {
			Map<MappingsDependency, List<String>> namespacesPerDependency = new HashMap<>();

			for (MappingsDependency mappingsDependency : this.mappingsDependencies) {
				// Extract mappings
				Path baseMappings = mappingsDependency.baseMappings();
				if (!Files.exists(baseMappings) || isRefreshDeps()) {
					try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsDependency.mappingsJar().toPath(), (ClassLoader) null)) {
						extractMappings(fileSystem, baseMappings);
					}
				}

				// Read namespaces
				TinyMetadata metadata;
				try (BufferedReader reader = Files.newBufferedReader(baseMappings)) {
					metadata = TinyV2Factory.readMetadata(reader);
				} catch (IllegalArgumentException e) {
					throw new UnsupportedOperationException("V1 tiny mappings are not supported!");
				}

				if (!metadata.getNamespaces().contains(Constants.Mappings.SOURCE_NAMESPACE)) {
					throw new IllegalArgumentException(String.format("All '%s' dependencies must contain the namespace %s (%s doesn't)",
							Constants.Configurations.INTERMEDIATE_MAPPINGS, Constants.Mappings.SOURCE_NAMESPACE, mappingsDependency.depString()));
				}
				namespacesPerDependency.put(mappingsDependency, metadata.getNamespaces());
			}

			if (this.mappingsDependencies.size() == 1) {
				Files.copy(this.mappingsDependencies.get(0).baseMappings(), this.tinyMappings.toPath());
			} else {
				// Merge mappings
				List<Path> mappingsToMerge = new ArrayList<>();
				for (Map.Entry<MappingsDependency, List<String>> dependency : namespacesPerDependency.entrySet()) {
					List<String> namespaces = new ArrayList<>(dependency.getValue());
					Path toMerge = dependency.getKey().baseMappings();
					// Reorder if needed
					if (!namespaces.get(0).equals(Constants.Mappings.SOURCE_NAMESPACE)) {
						getProject().getLogger().info(":reordering " + toMerge);
						namespaces.remove(Constants.Mappings.SOURCE_NAMESPACE);
						namespaces.add(0, Constants.Mappings.SOURCE_NAMESPACE);
						String filename = StringUtils.removeSuffix(toMerge.getFileName().toString(), ".tiny") + "-reordered.tiny";
						Path reordered = mappingsStepsDir.resolve(filename);
						reorderMappings(toMerge, reordered, namespaces.toArray(new String[0]));
						toMerge = reordered;
					}

					mappingsToMerge.add(toMerge);
				}

				getProject().getLogger().info(":merging intermediate mappings");
				mergeMappings(this.tinyMappings.toPath(), mappingsToMerge.toArray(new Path[0]));
			}
		}

		if (!this.tinyMappingsJar.exists() || isRefreshDeps()) {
			ZipUtil.pack(new ZipEntrySource[] {new FileSource(Constants.Mappings.MAPPINGS_FILE_PATH, this.tinyMappings)}, this.tinyMappingsJar);
		}

		addDependency(this.tinyMappingsJar, Constants.Configurations.MAPPINGS_FINAL);

		try (BufferedReader reader = Files.newBufferedReader(this.tinyMappings.toPath())) {
			this.tinyMappingsNamespaces = TinyV2Factory.readMetadata(reader).getNamespaces();
		}

		// TODO: REMOVE
		System.out.println(this);
	}

	@Override
	protected void mergeAndSaveMappings(Project project, Path unorderedMappingsJar) {
		throw new UnsupportedOperationException();
	}

	private void mergeMappings(Path outputMappings, Path... inputMappings) {
		try {
			Command command = new CommandMergeTinyV2();
			List<String> args = Arrays.stream(inputMappings).map(path -> path.toAbsolutePath().toString()).collect(Collectors.toList());
			args.add(outputMappings.toAbsolutePath().toString());
			runCommand(command, args.toArray(new String[0]));
		} catch (Exception e) {
			throw new RuntimeException("Could not merge mappings from " + inputMappings[0].toString()
					+ " with mappings from " + Arrays.toString(Arrays.copyOfRange(inputMappings, 1, 0)), e);
		}
	}

	public List<String> getNamespaces() {
		return new ArrayList<>(this.tinyMappingsNamespaces);
	}

	public List<String> getNamespacesExcept(String... excludedNamespaces) {
		List<String> namespaces = getNamespaces();
		namespaces.removeAll(List.of(excludedNamespaces));
		return namespaces;
	}

	@Override
	protected void initFiles() {
	}

	@Override
	public String toString() {
		return "IntermediateMappingsProviderImpl{" +
				"minecraftVersion='" + minecraftVersion + '\'' +
				", mappingsName='" + mappingsName + '\'' +
				", mappingsVersion='" + mappingsVersion + '\'' +
				", baseTinyMappings=" + baseTinyMappings +
				", tinyMappings=" + tinyMappings +
				", tinyMappingsJar=" + tinyMappingsJar +
				'}';
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.INTERMEDIATE_MAPPINGS;
	}

	@Override
	public boolean allowMultipleDependencies() {
		return true;
	}

	public static record MappingsDependency(File mappingsJar, Path baseMappings, String group, String name, String version, String depString) {
		public String getBaseName() {
			return name() + "-" + version();
		}
	}
}
