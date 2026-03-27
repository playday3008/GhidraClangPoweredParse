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
package playday3008.gcpp.task;

import playday3008.gcpp.GCPPPlugin;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;

import docking.widgets.dialogs.MultiLineMessageDialog;
import ghidra.program.database.data.ProgramDataTypeManager;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.FileDataTypeManager;
import ghidra.util.Msg;
import ghidra.util.task.Task;
import ghidra.util.task.TaskMonitor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Background task to parse C/C++ files using libclang.
 * Supports both "Parse to Program" (existing DTM) and "Parse to File" (new archive).
 */
public class ClangParseTask extends Task
{
    private static final Logger LOGGER = LogManager.getLogger();

    private final GCPPPlugin plugin;

    private String[] filenames;
    private String[] includePaths;
    private String options;

    private String languageId;
    private String compilerSpecId;
    private DataTypeManager[] openArchives;

    private final DataTypeManager dataTypeManager;
    private final File dataFile;

    /**
     * Parse to a new archive file.
     */
    public ClangParseTask(GCPPPlugin plugin, String dataFileName)
    {
        super("Parsing C/C++ Files (Clang Powered)", true, false, false);
        this.plugin = plugin;
        this.dataTypeManager = null;
        this.dataFile = new File(dataFileName);
    }

    /**
     * Parse to an existing DataTypeManager (e.g. the current program's).
     */
    public ClangParseTask(GCPPPlugin plugin, DataTypeManager dataTypeManager)
    {
        super("Parsing C/C++ Files (Clang Powered)", true, false, false);
        this.plugin = plugin;
        this.dataTypeManager = dataTypeManager;
        this.dataFile = null;
    }

    public ClangParseTask setFileNames(String[] names)
    {
        this.filenames = names.clone();
        return this;
    }

    public ClangParseTask setIncludePaths(String[] paths)
    {
        this.includePaths = paths.clone();
        return this;
    }

    public ClangParseTask setOptions(String options)
    {
        this.options = options;
        return this;
    }

    public ClangParseTask setLanguageID(String languageId)
    {
        this.languageId = languageId;
        return this;
    }

    public ClangParseTask setCompilerID(String compilerSpecId)
    {
        this.compilerSpecId = compilerSpecId;
        return this;
    }

    public ClangParseTask setOpenArchives(DataTypeManager[] openArchives)
    {
        this.openArchives = openArchives;
        return this;
    }

    @Override
    public void run(TaskMonitor monitor)
    {
        LOGGER.info("Starting clang parse task: {} source file(s)", filenames != null ? filenames.length : 0);

        FileDataTypeManager fileDtMgr = null;

        if (dataFile != null)
        {
            try
            {
                LOGGER.debug("Creating archive file: {}", dataFile.getAbsolutePath());
                fileDtMgr = FileDataTypeManager.createFileArchive(dataFile, languageId, compilerSpecId);
            }
            catch (IOException e)
            {
                LOGGER.error("Failed to create archive: {}", dataFile.getAbsolutePath(), e);
                showError("Archive Failure", "Failed to create archive: " + e.getMessage());
                return;
            }
        }

        DataTypeManager dtMgr = fileDtMgr != null ? fileDtMgr : dataTypeManager;
        int initialDtCount = dtMgr.getDataTypeCount(true);

        try
        {
            String langId = languageId;
            String compSpec = compilerSpecId;
            if (plugin.getProgram() != null)
            {
                var lcsPair = plugin.getProgram().getLanguageCompilerSpecPair();
                if (langId == null)
                    langId = lcsPair.languageID.getIdAsString();
                if (compSpec == null)
                    compSpec = lcsPair.compilerSpecID.getIdAsString();
            }

            LOGGER.debug("Parse target: {}, language={}, compiler={}", getParseDestination(dtMgr), langId, compSpec);
            String diagnostics = plugin.parseWithClang(filenames, includePaths, options, dtMgr, langId, compSpec, openArchives, monitor);

            if (fileDtMgr != null && dtMgr.getDataTypeCount(true) != 0)
                fileDtMgr.save();

            int added = dtMgr.getDataTypeCount(true) - initialDtCount;
            String destination = getParseDestination(dtMgr);
            LOGGER.info("Parse completed successfully: {} data types added to {}", added, destination);
            showSuccess(added, destination, diagnostics);
        }
        catch (Exception e)
        {
            int added = dtMgr.getDataTypeCount(true) - initialDtCount;
            LOGGER.error("Parse failed ({} types added before failure)", added, e);
            String countMsg = added > 0 ? added + " data types added before failure.\n\n" : "";
            showError("Clang Parse Failed", countMsg + e.getMessage());
        }
        finally
        {
            if (fileDtMgr != null)
            {
                boolean empty = fileDtMgr.getDataTypeCount(true) == 0;
                fileDtMgr.close();
                if (empty) {
                    LOGGER.debug("Deleting empty archive: {}", dataFile.getAbsolutePath());
                    dataFile.delete();
                }
            }
        }
    }

    private String getParseDestination(DataTypeManager dtMgr)
    {
        if (dtMgr instanceof ProgramDataTypeManager)
            return "Program " + dtMgr.getName();
        if (dtMgr instanceof FileDataTypeManager fileDtm)
            return "Archive File: " + fileDtm.getFilename();
        return dtMgr.getName();
    }

    private void showSuccess(int addedCount, String destination, String diagnostics)
    {
        String countMsg = (addedCount == 0 ? "No" : Integer.toString(addedCount)) + " data types added.";
        StringBuilder sb = new StringBuilder("<html><b>").append(countMsg).append("</b>");
        if (diagnostics != null && !diagnostics.isEmpty())
        {
            sb.append("<br><br><b>Clang Diagnostics:</b><br>");
            sb.append(diagnostics.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>"));
        }

        SwingUtilities.invokeLater(() ->
            MultiLineMessageDialog.showModalMessageDialog(
                plugin.getDialog().getComponent(),
                "Clang Parse Completed",
                "Parsed header file(s) to " + destination,
                sb.toString(),
                MultiLineMessageDialog.INFORMATION_MESSAGE));
    }

    private void showError(String title, String message)
    {
        SwingUtilities.invokeLater(() ->
            Msg.showError(this, plugin.getDialog().getComponent(), title, message));
    }
}
