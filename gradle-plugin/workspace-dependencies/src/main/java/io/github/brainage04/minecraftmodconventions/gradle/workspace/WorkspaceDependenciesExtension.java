package io.github.brainage04.minecraftmodconventions.gradle.workspace;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;

import javax.inject.Inject;

/** Typed local-sibling and released-artifact dependency policy. */
public abstract class WorkspaceDependenciesExtension {
    private final NamedDomainObjectContainer<WorkspaceDependencyDeclaration> declarations;

    @Inject
    public WorkspaceDependenciesExtension(ObjectFactory objects, ProjectLayout layout) {
        declarations = objects.domainObjectContainer(
                WorkspaceDependencyDeclaration.class,
                name -> objects.newInstance(WorkspaceDependencyDeclaration.class, name, objects, layout)
        );
    }

    public NamedDomainObjectContainer<WorkspaceDependencyDeclaration> getDeclarations() {
        return declarations;
    }

    public void siblingMaven(String name, Action<? super WorkspaceDependencyDeclaration> action) {
        WorkspaceDependencyDeclaration declaration = declarations.maybeCreate(name);
        action.execute(declaration);
    }
}
