/**
 * Copyright 2024 playday3008
 * Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted,
 * provided that the above copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE
 * INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE,
 * DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
 * CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package playday3008.gcpp;

import playday3008.gcpp.clang.error.ParseException;
import playday3008.gcpp.processing.SourceParser;
import playday3008.gcpp.processing.TypePool;
import playday3008.gcpp.task.ClangParseTask;
import playday3008.gcpp.ui.ClangParseDialog;
import docking.ActionContext;
import docking.action.DockingAction;
import docking.action.MenuData;
import docking.tool.ToolConstants;
import docking.widgets.OptionDialog;
import ghidra.app.CorePluginPackage;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.app.services.DataTypeManagerService;
import ghidra.framework.Application;
import ghidra.framework.options.SaveState;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.program.database.data.ProgramDataTypeManager;
import ghidra.program.model.data.BuiltInDataTypeManager;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.lang.LanguageCompilerSpecPair;
import ghidra.program.model.listing.Program;
import ghidra.util.*;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@PluginInfo(
        status = PluginStatus.STABLE,
        packageName = CorePluginPackage.NAME,
        category = PluginCategoryNames.ANALYSIS,
        shortDescription = "Clang C/C++ Parser",
        description = GCPPPlugin.DESCRIPTION,
        servicesRequired = { DataTypeManagerService.class }
)
public class GCPPPlugin extends ProgramPlugin
{
    public static final Logger LOGGER = LogManager.getLogger();

    static final String DESCRIPTION =
        "Parse C and C++ header files using libclang, extracting data type definitions.";

    private ClangParseDialog parseDialog;
    private File userProfileDir;

    public GCPPPlugin(PluginTool plugintool)
    {
        super(plugintool);
        createActions();
        userProfileDir = new File(
            Application.getUserSettingsDirectory().getAbsolutePath() +
            File.separatorChar + "parserprofiles");
        userProfileDir.mkdir();
    }

    public File getUserProfileDir()
    {
        return userProfileDir;
    }

    public Program getProgram()
    {
        return currentProgram;
    }

    public ClangParseDialog getDialog()
    {
        return parseDialog;
    }

    @Override
    public void dispose()
    {
        if (parseDialog != null)
        {
            parseDialog.close();
            parseDialog = null;
        }
    }

    @Override
    public void readDataState(SaveState saveState)
    {
        parseDialog = new ClangParseDialog(this);
        parseDialog.readState(saveState);
    }

    @Override
    public void writeDataState(SaveState saveState)
    {
        if (parseDialog != null)
            parseDialog.writeState(saveState);
    }

    @Override
    protected boolean canClose()
    {
        if (parseDialog != null)
            parseDialog.closeProfile();
        return true;
    }

    private void createActions()
    {
        DockingAction parseAction = new DockingAction("Parse C/C++ Source (Clang Powered)", getName())
        {
            @Override
            public void actionPerformed(ActionContext context)
            {
                showParseDialog();
            }
        };
        String[] menuPath = { ToolConstants.MENU_FILE, "Parse C/C++ Source (Clang Powered)..." };
        MenuData menuData = new MenuData(menuPath, "Import/Export");
        menuData.setMenuSubGroup("d");
        parseAction.setMenuBarData(menuData);
        parseAction.setDescription(DESCRIPTION);
        parseAction.setEnabled(true);
        tool.addAction(parseAction);
    }

    private void showParseDialog()
    {
        if (parseDialog == null)
            parseDialog = new ClangParseDialog(this);
        parseDialog.setupForDisplay();
        tool.showDialog(parseDialog);
    }

    /**
     * Parse to the current program's DataTypeManager.
     */
    public void parse(String[] filenames, String[] includePaths, String options)
    {
        if (currentProgram == null)
        {
            Msg.showInfo(getClass(), parseDialog.getComponent(), "No Open Program",
                "A program must be open to \"Parse to Program\"");
            return;
        }

        LanguageCompilerSpecPair lcsPair = currentProgram.getLanguageCompilerSpecPair();
        String procID = lcsPair.languageID.getIdAsString();
        String compilerID = lcsPair.compilerSpecID.getIdAsString();

        int confirm = OptionDialog.showOptionDialog(parseDialog.getComponent(), "Confirm",
            "Parse C/C++ source to \"" + currentProgram.getDomainFile().getName() + "\"?" + "\n\n" +
                "Using program architecture: " + procID + " / " + compilerID, "Continue");

        if (confirm == OptionDialog.CANCEL_OPTION)
            return;

        DataTypeManager[] openDTMgrs;
        try
        {
            openDTMgrs = getOpenDTMgrs();
        }
        catch (CancelledException e)
        {
            return;
        }

        ClangParseTask task = new ClangParseTask(this, currentProgram.getDataTypeManager())
            .setFileNames(filenames)
            .setIncludePaths(includePaths)
            .setOptions(options)
            .setOpenArchives(openDTMgrs);

        tool.execute(task);
    }

    /**
     * Parse to a file (creates a new .gdt archive).
     */
    public void parse(String[] filenames, String[] includePaths, String options,
                      String languageIDString, String compilerSpecID, String dataFilename)
    {
        DataTypeManager[] openDTMgrs;
        try
        {
            openDTMgrs = getOpenDTMgrs();
        }
        catch (CancelledException e)
        {
            return;
        }

        ClangParseTask task = new ClangParseTask(this, dataFilename)
            .setFileNames(filenames)
            .setIncludePaths(includePaths)
            .setOptions(options)
            .setLanguageID(languageIDString)
            .setCompilerID(compilerSpecID)
            .setOpenArchives(openDTMgrs);

        tool.execute(task, 500);
    }

    /**
     * Prompt the user whether to use currently open archives for type resolution.
     * Matches the behavior of the built-in CParserPlugin.
     *
     * @return array of open DTMs to use, or null if user chose not to use them
     * @throws CancelledException if user cancelled
     */
    private DataTypeManager[] getOpenDTMgrs() throws CancelledException
    {
        DataTypeManagerService dtService = tool.getService(DataTypeManagerService.class);
        if (dtService == null)
            return null;

        DataTypeManager[] allDTMs = dtService.getDataTypeManagers();

        ArrayList<DataTypeManager> list = new ArrayList<>();
        StringBuilder htmlNamesList = new StringBuilder();
        for (DataTypeManager dtm : allDTMs)
        {
            if (dtm instanceof ProgramDataTypeManager)
                continue;
            list.add(dtm);
            if (!(dtm instanceof BuiltInDataTypeManager))
                htmlNamesList.append("<li><b>").append(HTMLUtilities.escapeHTML(dtm.getName())).append("</b></li>");
        }

        DataTypeManager[] openDTMgrs = list.toArray(new DataTypeManager[0]);

        if (openDTMgrs.length > 1)
        {
            int result = OptionDialog.showOptionDialog(
                parseDialog.getComponent(), "Use Open Archives?",
                "<html>The following archives are currently open: " +
                    "<ul>" + htmlNamesList + "</ul>" +
                    "<p><b>The new archive will become dependent on these archives<br>" +
                    "for any datatypes already defined in them </b>(only unique <br>" +
                    "data types will be added to the new archive).",
                "Use Open Archives", "Don't Use Open Archives", OptionDialog.QUESTION_MESSAGE);

            if (result == OptionDialog.CANCEL_OPTION)
                throw new CancelledException("User Cancelled");
            if (result == OptionDialog.OPTION_TWO)
                return null;
        }

        return openDTMgrs;
    }

    /**
     * Core parse method called by {@link ClangParseTask}.
     * Parses source files via libclang, resolves types, and commits them to the DTM.
     *
     * @param filenames     source files to parse
     * @param includePaths  include directories for header resolution
     * @param options       parse options (one per line, e.g. -D flags)
     * @param dtMgr         target DataTypeManager to commit types to
     * @param languageId    Ghidra LanguageID string (e.g. "x86:LE:64:default")
     * @param compilerSpec  Ghidra CompilerSpecID string (e.g. "gcc", "windows")
     * @param openArchives  additional DTMs to use for type resolution (may be null)
     * @param monitor       task monitor for progress reporting
     * @return diagnostic messages from clang
     * @throws ParseException if clang parsing fails
     */
    public String parseWithClang(String[] filenames, String[] includePaths, String options,
                                 DataTypeManager dtMgr, String languageId, String compilerSpec,
                                 DataTypeManager[] openArchives, TaskMonitor monitor)
        throws ParseException
    {
        TypePool typePool = new TypePool(openArchives);
        try
        {
            // Phase 1: Parse with clang
            monitor.setMessage("Parsing " + filenames.length + " source file(s) with clang...");
            SourceParser parser = new SourceParser();

            List<String> diagnostics = parser.parseFiles(typePool, filenames, includePaths, options, languageId, compilerSpec);

            // Phase 2: Resolve type dependencies
            monitor.setMessage("Resolving type dependencies...");
            TypePool.ResolutionResult result = typePool.resolve();

            // Phase 3: Commit resolved types to the target DTM
            List<DataType> dataTypes = result.getDataTypes();
            monitor.setMessage("Committing " + dataTypes.size() + " data types...");
            monitor.setMaximum(dataTypes.size());
            monitor.setProgress(0);

            int transaction = dtMgr.startTransaction("Add clang-parsed data types");
            try
            {
                int count = 0;
                for (DataType t : dataTypes)
                {
                    monitor.setProgress(++count);
                    monitor.setMessage("Adding: " + t.getName());
                    try
                    {
                        dtMgr.addDataType(t, DataTypeConflictHandler.REPLACE_HANDLER);
                    }
                    catch (Exception e)
                    {
                        LOGGER.warn("Failed to add type: " + t.getName(), e);
                    }
                }
            }
            finally
            {
                dtMgr.endTransaction(transaction, true);
            }

            // Build result message with diagnostics and any unresolved warnings
            StringBuilder messages = new StringBuilder();
            if (!diagnostics.isEmpty())
                messages.append(String.join("\n", diagnostics));

            var unresolved = result.getUnresolvedDependencies();
            if (!unresolved.isEmpty())
            {
                LOGGER.warn("Unresolved type dependencies (skipped): " + unresolved);
                if (!messages.isEmpty())
                    messages.append("\n");
                messages.append(unresolved.size()).append(" types skipped due to unresolved dependencies.");
            }

            return messages.toString();
        }
        finally
        {
            typePool.close();
        }
    }
}
