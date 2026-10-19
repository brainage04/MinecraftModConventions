package io.github.brainage04.minecraftmodconventions.gradle.workspace;

import org.gradle.api.Named;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/** Describes one Maven artifact that may be supplied by a sibling workspace checkout. */
public abstract class WorkspaceDependencyDeclaration implements Named {
    private final String name;
    private final Property<String> coordinate;
    private final DirectoryProperty siblingDirectory;
    private final Property<String> localRepository;

    @Inject
    public WorkspaceDependencyDeclaration(String name, ObjectFactory objects, ProjectLayout layout) {
        this.name = name;
        coordinate = objects.property(String.class);
        siblingDirectory = objects.directoryProperty()
                .convention(layout.getProjectDirectory().dir("../" + name));
        localRepository = objects.property(String.class).convention("build/local-repo");
    }

    @Override
    public String getName() {
        return name;
    }

    public Property<String> getCoordinate() {
        return coordinate;
    }

    public DirectoryProperty getSiblingDirectory() {
        return siblingDirectory;
    }

    public Property<String> getLocalRepository() {
        return localRepository;
    }

}
