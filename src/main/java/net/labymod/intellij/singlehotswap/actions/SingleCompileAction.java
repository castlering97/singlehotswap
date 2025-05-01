package net.labymod.intellij.singlehotswap.actions;

import com.intellij.compiler.actions.CompileAction;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import net.labymod.intellij.singlehotswap.compiler.impl.BuiltInJavaCompiler;
import net.labymod.intellij.singlehotswap.hotswap.ClassFile;
import net.labymod.intellij.singlehotswap.hotswap.impl.AbstractContext;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

public class SingleCompileAction extends CompileAction {

    @Override
    public void update(AnActionEvent event) {
        Project project = event.getProject();
        Presentation presentation = event.getPresentation();

        if (project == null) {
            presentation.setEnabled(false);
            return;
        }

        // Get required instances
        VirtualFile[] availableFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        VirtualFile[] compilableFiles = getCompilableFiles(project, availableFiles);
        CompilerManager compileManager = CompilerManager.getInstance(project);

        // Get the current target file
        PsiFile currentFile = event.getData(CommonDataKeys.PSI_FILE);

        // Define button state
        boolean enabled = currentFile instanceof PsiJavaFile
                && compilableFiles.length > 0
                && !compileManager.isCompilationActive();

        // Update state
        presentation.setEnabled(enabled);
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        try {
            PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
            if (psiFile == null || !(psiFile instanceof PsiJavaFile)) {
                return;
            }

            Project project = psiFile.getProject();
            
            // Save the opened documents
            FileDocumentManager.getInstance().saveAllDocuments();

            // Create compiler
            BuiltInJavaCompiler compiler = new BuiltInJavaCompiler(new SimpleJavaContext());

            try {
                ClassFile outputFile = getClassFile(psiFile);
                VirtualFile sourceFile = psiFile.getVirtualFile();

                Module module = ProjectFileIndex.getInstance(project).getModuleForFile(sourceFile);
                if (module == null) {
                    notifyUser("Could not find module for file: " + sourceFile.getName(), NotificationType.WARNING);
                    return;
                }

                // Compile
                long start = System.currentTimeMillis();
                List<ClassFile> classFiles = compiler.compile(module, sourceFile, outputFile);
                
                if (classFiles.isEmpty()) {
                    notifyUser("Could not compile " + psiFile.getName(), NotificationType.ERROR);
                    return;
                }

                // Show compile duration
                long duration = System.currentTimeMillis() - start;
                notifyUser("Compiled " + classFiles.size() + " classes in " + duration + "ms", NotificationType.INFORMATION);

            } catch (FileNotFoundException e) {
                notifyUser("Could not find output class file: " + e.getMessage(), NotificationType.ERROR);
            } catch (Exception e) {
                notifyUser("Error during compilation: " + e.getMessage(), NotificationType.ERROR);
            }
        } catch (Exception e) {
            notifyUser("Error: " + e.getMessage(), NotificationType.ERROR);
        }
    }

    private ClassFile getClassFile(PsiFile psiFile) throws FileNotFoundException {
        Project project = psiFile.getProject();
        String fileName = psiFile.getName();
        String className = fileName.substring(0, fileName.toLowerCase().lastIndexOf(".java"));
        String packageName = ((PsiJavaFile) psiFile).getPackageName();

        // Create the full class name
        String classPath = packageName.isEmpty() ? className : (packageName + "." + className);

        // Find the class name in the output paths of the project
        String[] outputPaths = com.intellij.openapi.compiler.CompilerPaths.getOutputPaths(com.intellij.openapi.module.ModuleManager.getInstance(project).getModules());
        for (String outputPath : outputPaths) {
            // Create file instance using the output path and the class name
            File file = new File(String.format("%s/%s.class", outputPath, classPath.replace('.', '/')));
            if (!file.exists()) {
                continue;
            }

            // Create class file instance
            return new ClassFile(project, file, packageName, className);
        }

        throw new FileNotFoundException("Could not find class file in output path");
    }

    private void notifyUser(String message, NotificationType type) {
        Notification notification = new Notification("SingleCompile", "Single Compile", message, type);
        Notifications.Bus.notify(notification);
    }

    private static class SimpleJavaContext extends AbstractContext<PsiJavaFile> {
        @Override
        protected String getPackageName(PsiJavaFile file) {
            return file.getPackageName();
        }

        @Override
        protected String getExtensionName() {
            return "java";
        }

        @Override
        public boolean isPossible(PsiFile file) {
            return true;
        }
    }
} 