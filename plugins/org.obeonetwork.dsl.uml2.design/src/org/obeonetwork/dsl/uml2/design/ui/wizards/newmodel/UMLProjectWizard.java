package org.obeonetwork.dsl.uml2.design.ui.wizards.newmodel;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.actions.WorkspaceModifyOperation;
import org.eclipse.ui.dialogs.WizardNewProjectCreationPage;
import org.eclipse.ui.wizards.newresource.BasicNewProjectResourceWizard;
import org.eclipse.uml2.uml.UMLFactory;
import org.eclipse.uml2.uml.UMLPackage;
import org.obeonetwork.dsl.uml2.design.UMLDesignerPlugin;

import com.google.common.collect.Maps;

import fr.obeo.dsl.common.tools.api.util.Option;
import fr.obeo.dsl.common.tools.api.util.Options;
import fr.obeo.dsl.viewpoint.business.api.componentization.ViewpointRegistry;
import fr.obeo.dsl.viewpoint.business.api.modelingproject.ModelingProject;
import fr.obeo.dsl.viewpoint.business.api.session.Session;
import fr.obeo.dsl.viewpoint.description.Viewpoint;
import fr.obeo.dsl.viewpoint.ui.business.api.viewpoint.ViewpointSelectionCallback;
import fr.obeo.dsl.viewpoint.ui.tools.api.project.ModelingProjectManager;

public class UMLProjectWizard extends BasicNewProjectResourceWizard {
	/**
	 * Dot constant.
	 */
	public static final String DOT = ".";

	/**
	 * The UML file extension.
	 */
	public static final String MODEL_FILE_EXTENSION = "uml"; //$NON-NLS-1$

	/**
	 * UML structural viewpoint name defined in odesign.
	 */
	public static final String UML_STRUCTURAL_VP = "UML Structural Modeling";

	/**
	 * UML behavioral viewpoint name defined in odesign.
	 */
	public static final String UML_BEHAVIORAL_VP = "UML Behavioral Modeling";

	protected UmlModelWizardInitModelPage modelPage;

	protected WizardNewProjectCreationPage newProjectPage;

	@Override
	public void addPages() {
		// we're not calling the super as we want to control the project creation, we don't want the default
		// page.
		// super.addPages();

		newProjectPage = new WizardNewProjectCreationPage("Project"); //$NON-NLS-1$
		newProjectPage.setInitialProjectName("");
		newProjectPage.setTitle("Create a UML Modeling project");
		newProjectPage.setDescription("Enter a project name"); //$NON-NLS-1$
		addPage(newProjectPage);

		modelPage = new UmlModelWizardInitModelPage(Messages.UmlModelWizard_UI_InitModelPageId);
		modelPage.setTitle(Messages.UmlModelWizard_UI_InitModelPageTitle);
		modelPage.setDescription(Messages.UmlModelWizard_UI_InitModelPageDescription);
		addPage(modelPage);
	}

	@Override
	public boolean performFinish() {
		try {
			final InitProject runnable = new InitProject(newProjectPage.getProjectName(),
					newProjectPage.getLocationPath(), modelPage.getInitialObjectName());
			getContainer().run(true, false, runnable);
			updatePerspective();
			Display.getDefault().syncExec(new Runnable() {

				public void run() {
					runnable.enableViewpointsAndReveal(UML_STRUCTURAL_VP, UML_BEHAVIORAL_VP);
				}
			});
		} catch (InvocationTargetException e) {
			UMLDesignerPlugin.log(IStatus.ERROR, Messages.UmlModelWizard_UI_Error_CreatingUmlModel, e);
			return false;
		} catch (InterruptedException e) {
			UMLDesignerPlugin.log(IStatus.ERROR, Messages.UmlModelWizard_UI_Error_CreatingUmlModel, e);
			return false;
		}
		return true;

	}

	public class InitProject extends WorkspaceModifyOperation {

		private String projectName;

		private IPath locationPath;

		private String initialObjectName;

		private Session session;

		private IProject project;

		private Option<IFile> optionalNewfile;

		public InitProject(String projectName, IPath locationPath, String initialObjectName) {
			this.projectName = projectName;
			this.locationPath = locationPath;
			this.initialObjectName = initialObjectName;
		}

		@Override
		protected void execute(IProgressMonitor monitor) throws CoreException, InvocationTargetException,
				InterruptedException {
			project = ModelingProjectManager.INSTANCE.createNewModelingProject(projectName, locationPath,
					true);
			optionalNewfile = createSemanticResource(project);

		}

		public void enableViewpointsAndReveal(final String... viewpointsToActivate) {
			if (session != null) {
				session.getTransactionalEditingDomain().getCommandStack()
						.execute(new RecordingCommand(session.getTransactionalEditingDomain()) {
							@Override
							protected void doExecute() {
								ViewpointSelectionCallback callback = new ViewpointSelectionCallback();

								for (Viewpoint vp : ViewpointRegistry.getInstance().getViewpoints()) {
									for (String viewpoint : viewpointsToActivate) {
										if (viewpoint.equals(vp.getName()))
											callback.selectViewpoint(vp, session);
									}
								}
							}
						});
				if (optionalNewfile.some() && optionalNewfile.get().exists()) {
					selectAndReveal(optionalNewfile.get());
				} else {
					selectAndReveal(project);
				}
			}
		}

		private Option<IFile> createSemanticResource(final IProject project) {
			Option<ModelingProject> modelingProject = ModelingProject.asModelingProject(project);
			if (modelingProject.some()) {
				session = modelingProject.get().getSession();
			} else {
				session = null;
			}
			if (session == null) {
				return Options.newNone();
			}

			final String platformPath = '/' + project.getName() + '/' + initialObjectName.toLowerCase() + DOT
					+ MODEL_FILE_EXTENSION;
			session.getTransactionalEditingDomain().getCommandStack()
					.execute(new RecordingCommand(session.getTransactionalEditingDomain()) {
						@Override
						protected void doExecute() {

							final URI semanticModelURI = URI.createPlatformResourceURI(platformPath, true);
							Resource res = new ResourceSetImpl().createResource(semanticModelURI);
							/* Add the initial model object to the contents. */
							final EObject rootObject = createInitialModel(initialObjectName);

							if (rootObject != null) {
								res.getContents().add(rootObject);
							}
							try {
								res.save(Maps.newHashMap());
							} catch (IOException e) {
								UMLDesignerPlugin.log(IStatus.ERROR,
										Messages.UmlModelWizard_UI_Error_CreatingUmlModel, e);
							}

							session.addSemanticResource(semanticModelURI, true);

							session.save();
						}
					});
			return Options.newSome(ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(platformPath)));
		}

		/**
		 * Creates the semantic root element from the given operation arguments.
		 * 
		 * @param initialObjectName2
		 * @return the semantic root {@link EObject}
		 */
		private EObject createInitialModel(String initialObjectName2) {
			EClassifier found = UMLPackage.eINSTANCE.getEClassifier(initialObjectName2);
			if (found instanceof EClass) {
				return UMLFactory.eINSTANCE.create((EClass)found);
			} else {
				return UMLFactory.eINSTANCE.createModel();
			}
		}

	}

}
