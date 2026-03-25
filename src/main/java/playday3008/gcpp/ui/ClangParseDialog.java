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
package playday3008.gcpp.ui;

import playday3008.gcpp.GCPPPlugin;

import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.*;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.TableModel;

import docking.*;
import docking.action.*;
import docking.widgets.OptionDialog;
import docking.widgets.button.BrowseButton;
import docking.widgets.combobox.GhidraComboBox;
import docking.widgets.dialogs.InputDialog;
import docking.widgets.filechooser.GhidraFileChooser;
import docking.widgets.filechooser.GhidraFileChooserMode;
import docking.widgets.label.GLabel;
import docking.widgets.pathmanager.PathnameTablePanel;
import docking.widgets.table.GTableCellRenderer;
import docking.widgets.table.GTableCellRenderingData;
import generic.jar.ResourceFile;
import generic.theme.Gui;
import ghidra.app.plugin.core.processors.SetLanguageDialog;
import ghidra.framework.Application;
import ghidra.framework.options.SaveState;
import ghidra.framework.preferences.Preferences;
import ghidra.framework.store.db.PackedDatabase;
import ghidra.program.model.data.FileDataTypeManager;
import ghidra.program.model.lang.CompilerSpecID;
import ghidra.program.model.lang.LanguageID;
import ghidra.util.Msg;
import ghidra.util.filechooser.ExtensionFileFilter;
import resources.Icons;

/**
 * Dialog for parsing C/C++ source files using clang. Closely mirrors Ghidra's built-in
 * ParseDialog for "Parse C Source" but uses libclang as the parsing backend.
 *
 * Uses the same .prf profile format and directories as the built-in C parser.
 */
public class ClangParseDialog extends ReusableDialogComponentProvider
{
    static final String PROFILE_DIR = "parserprofiles";
    private static final String FILE_EXTENSION = ".prf";

    private static final String CURRENT_PROFILE = "GCPPCurrentProfile";
    private static final String USER_DEFINED = "GCPPIsUserDefined";
    private static final String LAST_IMPORT_C_DIRECTORY = "LastImportCDirectory";

    private JPanel mainPanel;
    private final GCPPPlugin plugin;
    private JButton parseButton;
    private JButton parseToFileButton;

    private PathnameTablePanel pathPanel;
    private JTextArea parseOptionsField;

    private JComponent languagePanel;
    private JTextField languageTextField;
    private JButton languageButton;
    private String languageIDString = null;
    private String compilerIDString = null;

    private GhidraComboBox<ComboBoxItem> comboBox;
    private DefaultComboBoxModel<ComboBoxItem> comboModel;
    private DockingAction saveAction;
    private DockingAction saveAsAction;
    private DockingAction clearAction;
    private DockingAction deleteAction;
    private DockingAction refreshAction;
    private DocumentListener docListener;
    private TableModelListener tableListener;
    private ItemListener comboItemListener;
    private TableModel tableModel;

    private PathnameTablePanel includePathPanel;
    private TableModel parsePathTableModel;
    private TableModelListener parsePathTableListener;

    private List<ComboBoxItem> itemList;
    private ComboBoxItemComparator comparator;
    private ResourceFile parentUserFile;
    private boolean saveAsInProgress;
    private boolean initialBuild = true;

    private boolean userDefined = false;
    private String currentProfileName = null;

    public ClangParseDialog(GCPPPlugin plugin)
    {
        super("Parse C/C++ Source (Clang Powered)", false, true, true, false);
        this.plugin = plugin;
    }

    public void setupForDisplay()
    {
        if (!initialBuild)
        {
            toFront();
            return;
        }

        itemList = new ArrayList<>();
        comparator = new ComboBoxItemComparator();
        addWorkPanel(buildMainPanel());
        addDismissButton();
        createActions();
        notifyContextChanged();

        // Restore saved profile selection
        if (currentProfileName != null)
        {
            for (int i = 0; i < itemList.size(); i++)
            {
                ComboBoxItem item = itemList.get(i);
                if (userDefined == item.isUserDefined &&
                    currentProfileName.equals(item.file.getName()))
                {
                    comboBox.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    public void writeState(SaveState saveState)
    {
        if (!initialBuild)
        {
            ComboBoxItem item = (ComboBoxItem) comboBox.getSelectedItem();
            currentProfileName = item.file.getName();
            userDefined = item.isUserDefined;
        }
        saveState.putString(CURRENT_PROFILE, currentProfileName);
        saveState.putBoolean(USER_DEFINED, userDefined);
    }

    public void readState(SaveState saveState)
    {
        currentProfileName = saveState.getString(CURRENT_PROFILE, null);
        if (currentProfileName != null)
            userDefined = saveState.getBoolean(USER_DEFINED, true);
    }

    public void closeProfile()
    {
        if (initialBuild)
            return;
        ComboBoxItem item = (ComboBoxItem) comboBox.getSelectedItem();
        if (item != null && item.isChanged)
            processItemChanged(item);
    }

    @Override
    protected TaskScheduler getTaskScheduler()
    {
        return super.getTaskScheduler();
    }

    private JPanel buildMainPanel()
    {
        initialBuild = true;

        mainPanel = new JPanel(new BorderLayout(10, 5));

        comboModel = new DefaultComboBoxModel<>();
        populateComboBox();

        comboBox = new GhidraComboBox<>(comboModel);
        comboItemListener = this::selectionChanged;
        comboBox.getAccessibleContext().setAccessibleName("Parse Configurations");
        comboBox.addItemListener(comboItemListener);

        JPanel cPanel = new JPanel(new BorderLayout());
        cPanel.setBorder(BorderFactory.createTitledBorder("Parse Configuration"));
        cPanel.add(comboBox);
        JPanel comboPanel = new JPanel(new BorderLayout());
        comboPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        comboPanel.add(cPanel);

        // Source files panel — editable, add to bottom, ordered
        pathPanel = new PathnameTablePanel(null, true, false, true);
        pathPanel.setBorder(BorderFactory.createTitledBorder("Source files to parse"));
        String importDir = Preferences.getProperty(LAST_IMPORT_C_DIRECTORY);
        if (importDir == null)
        {
            importDir = Preferences.getProperty(Preferences.LAST_PATH_DIRECTORY);
            if (importDir != null)
                Preferences.setProperty(LAST_IMPORT_C_DIRECTORY, importDir);
        }
        pathPanel.setFileChooserProperties("Choose Source Files", LAST_IMPORT_C_DIRECTORY,
            GhidraFileChooserMode.FILES_AND_DIRECTORIES, true,
            new ExtensionFileFilter(new String[]{"h"}, "C Header Files"));

        // Render missing files in red
        pathPanel.getTable().setDefaultRenderer(String.class, new GTableCellRenderer()
        {
            @Override
            public Component getTableCellRendererComponent(GTableCellRenderingData data)
            {
                JLabel label = (JLabel) super.getTableCellRendererComponent(data);
                Object value = data.getValue();
                String pathName = (value == null ? "" : ((String) value).trim());

                if (pathName.isEmpty() || pathName.startsWith("#"))
                    return label;

                boolean fileExists = new File(pathName).exists();
                if (!fileExists)
                    fileExists = doesFileExist(pathName);

                label.setText(pathName);
                if (!fileExists)
                    label.setForeground(getErrorForegroundColor(data.isSelected()));

                return label;
            }
        });

        tableListener = e -> {
            ComboBoxItem item = (ComboBoxItem) comboBox.getSelectedItem();
            item.isChanged = !initialBuild;
            notifyContextChanged();
        };
        tableModel = pathPanel.getTable().getModel();
        tableModel.addTableModelListener(tableListener);

        // Include paths panel — editable, add to bottom, ordered
        includePathPanel = new PathnameTablePanel(null, true, false, true);
        includePathPanel.setBorder(BorderFactory.createTitledBorder("Include paths"));
        includePathPanel.setFileChooserProperties("Choose Include Directory", LAST_IMPORT_C_DIRECTORY,
            GhidraFileChooserMode.DIRECTORIES_ONLY, true, null);

        parsePathTableListener = e -> {
            ComboBoxItem item = (ComboBoxItem) comboBox.getSelectedItem();
            item.isChanged = !initialBuild;
            notifyContextChanged();
            pathPanel.getTable().repaint();
        };
        parsePathTableModel = includePathPanel.getTable().getModel();
        parsePathTableModel.addTableModelListener(parsePathTableListener);

        // Parse options panel
        JPanel optionsPanel = new JPanel(new BorderLayout());
        optionsPanel.setBorder(BorderFactory.createTitledBorder("Parse Options"));
        parseOptionsField = new JTextArea(5, 70);
        JScrollPane pane = new JScrollPane(parseOptionsField);
        pane.getViewport().setPreferredSize(new Dimension(300, 200));
        optionsPanel.add(pane, BorderLayout.CENTER);

        // Architecture panel
        JPanel archPanel = new JPanel(new BorderLayout());
        archPanel.setBorder(BorderFactory.createTitledBorder("Program Architecture:"));
        archPanel.add(new GLabel(" ", SwingConstants.RIGHT));
        languagePanel = buildLanguagePanel();
        archPanel.add(languagePanel);

        // Parse buttons
        parseButton = new JButton("Parse to Program");
        parseButton.addActionListener(ev -> doParse(false));
        parseButton.setToolTipText("Parse files and add data types to current program");
        addButton(parseButton);

        parseToFileButton = new JButton("Parse to File...");
        parseToFileButton.addActionListener(ev -> doParse(true));
        parseToFileButton.setToolTipText("Parse files and output to archive file");
        addButton(parseToFileButton);

        // Assemble layout
        mainPanel.add(comboPanel, BorderLayout.NORTH);

        includePathPanel.setPreferredSize(new Dimension(pathPanel.getPreferredSize().width, 200));
        JSplitPane optionsPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, includePathPanel, optionsPanel);
        optionsPane.setResizeWeight(0.50);

        pathPanel.setPreferredSize(new Dimension(pathPanel.getPreferredSize().width, 200));
        JSplitPane outerPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pathPanel, optionsPane);
        outerPane.setResizeWeight(0.50);

        mainPanel.add(outerPane, BorderLayout.CENTER);
        mainPanel.add(archPanel, BorderLayout.SOUTH);

        if (comboBox.getSelectedItem() != null)
            loadProfile();

        initialBuild = false;
        return mainPanel;
    }

    private boolean doesFileExist(String pathName)
    {
        String[] paths = includePathPanel.getPaths();
        for (String path : paths)
        {
            File file = new File(path, pathName);
            if (file.exists())
                return true;
        }
        return false;
    }

    private JComponent buildLanguagePanel()
    {
        languageTextField = new JTextField();
        languageTextField.setEditable(false);
        languageTextField.setFocusable(false);

        languageButton = new BrowseButton();
        languageButton.addActionListener(e -> {
            SetLanguageDialog dialog = new SetLanguageDialog(plugin.getTool(), languageIDString,
                compilerIDString, "Select Program Architecture for File DataType Archive");
            LanguageID languageId = dialog.getLanguageDescriptionID();
            CompilerSpecID compilerSpecId = dialog.getCompilerSpecDescriptionID();
            if (languageId == null || compilerSpecId == null)
                return;

            String newLanguageIDString = languageId.getIdAsString();
            String newCompilerIDString = compilerSpecId.getIdAsString();

            if (!Objects.equals(newLanguageIDString, languageIDString) ||
                !Objects.equals(newCompilerIDString, compilerIDString))
            {
                itemChanged();
            }

            languageIDString = newLanguageIDString;
            compilerIDString = newCompilerIDString;
            updateArchitectureDescription();
        });

        updateArchitectureDescription();

        languageButton.setName("Set Processor Architecture");
        Gui.registerFont(languageButton, Font.BOLD);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(languageTextField, BorderLayout.CENTER);
        panel.add(languageButton, BorderLayout.EAST);
        return panel;
    }

    private void updateArchitectureDescription()
    {
        String description = "64/32 (primarily for backward compatibility)";

        if (languageIDString != null)
        {
            StringBuilder buf = new StringBuilder();
            buf.append(languageIDString);
            buf.append("  /  ");
            buf.append(compilerIDString != null ? compilerIDString : "none");
            description = buf.toString();
        }

        languageTextField.setText(description);
    }

    private void selectionChanged(ItemEvent e)
    {
        if (e.getStateChange() == ItemEvent.SELECTED)
        {
            loadProfile();
            return;
        }

        ComboBoxItem item = (ComboBoxItem) e.getItem();
        if (!item.isChanged || saveAsInProgress || initialBuild)
            return;

        if (item.isUserDefined)
        {
            if (OptionDialog.showOptionDialog(rootPanel, "Save Changes to Profile?",
                "Profile " + item.file.getName() + " has changed.\nDo you want to save your changes?",
                "Yes", OptionDialog.QUESTION_MESSAGE) == OptionDialog.OPTION_ONE)
            {
                save(item);
            }
        }
        else
        {
            if (OptionDialog.showOptionDialog(rootPanel, "Save Changes to Another Profile?",
                "You have made changes to the default profile " + item.file.getName() +
                    ",\nhowever, updating default profiles is not allowed." +
                    "\nDo you want to save your changes to another profile?",
                "Yes", OptionDialog.QUESTION_MESSAGE) == OptionDialog.OPTION_ONE)
            {
                saveAs(item);
            }
        }
    }

    private void processItemChanged(ComboBoxItem item)
    {
        if (item.isUserDefined)
        {
            if (OptionDialog.showOptionDialog(rootPanel, "Save Changes to Profile?",
                "Profile " + item.file.getName() + " has changed.\nDo you want to save your changes?",
                "Yes", OptionDialog.QUESTION_MESSAGE) == OptionDialog.OPTION_ONE)
            {
                save(item);
            }
        }
        else
        {
            if (OptionDialog.showOptionDialog(rootPanel, "Save Changes to Another Profile?",
                "You have made changes to the default profile " + item.file.getName() +
                    ",\nhowever, updating default profiles is not allowed." +
                    "\nDo you want to save your changes to another profile?",
                "Yes", OptionDialog.QUESTION_MESSAGE) == OptionDialog.OPTION_ONE)
            {
                saveAs(item);
            }
        }
    }

    private void addDocumentListener()
    {
        if (docListener == null)
        {
            docListener = new DocumentListener()
            {
                @Override
                public void changedUpdate(DocumentEvent e) { itemChanged(); }

                @Override
                public void insertUpdate(DocumentEvent e) { itemChanged(); }

                @Override
                public void removeUpdate(DocumentEvent e) { itemChanged(); }
            };
        }
        parseOptionsField.getDocument().addDocumentListener(docListener);
    }

    private void itemChanged()
    {
        ComboBoxItem item = (ComboBoxItem) comboBox.getSelectedItem();
        if (item == null)
            return;
        item.isChanged = true;
        notifyContextChanged();
    }

    private void createActions()
    {
        saveAction = new DockingAction("Save Profile", plugin.getName())
        {
            @Override
            public void actionPerformed(ActionContext context)
            {
                save((ComboBoxItem) comboBox.getSelectedItem());
            }

            @Override
            public boolean isEnabledForContext(ActionContext context)
            {
                ComboBoxItem item = (ComboBoxItem) comboBox.getSelectedItem();
                return item != null && item.isChanged && item.isUserDefined;
            }
        };
        Icon icon = Icons.SAVE_ICON;
        String saveGroup = "save";
        saveAction.setMenuBarData(new MenuData(new String[]{"Save"}, icon, saveGroup));
        saveAction.setToolBarData(new ToolBarData(icon, saveGroup));
        saveAction.setDescription("Save profile");
        addAction(saveAction);

        saveAsAction = new DockingAction("Save Profile As", plugin.getName())
        {
            @Override
            public void actionPerformed(ActionContext context)
            {
                saveAs((ComboBoxItem) comboBox.getSelectedItem());
            }

            @Override
            public boolean isEnabledForContext(ActionContext context)
            {
                return true;
            }
        };
        icon = Icons.SAVE_AS_ICON;
        saveAsAction.setMenuBarData(new MenuData(new String[]{"Save As..."}, icon, saveGroup));
        saveAsAction.setToolBarData(new ToolBarData(icon, saveGroup));
        saveAsAction.setDescription("Save profile to new name");
        addAction(saveAsAction);

        clearAction = new DockingAction("Clear Profile", plugin.getName())
        {
            @Override
            public void actionPerformed(ActionContext context) { clear(); }

            @Override
            public boolean isEnabledForContext(ActionContext context) { return true; }
        };
        icon = Icons.CLEAR_ICON;
        String clearGroup = "clear";
        clearAction.setMenuBarData(new MenuData(new String[]{"Clear Profile"}, icon, clearGroup));
        clearAction.setToolBarData(new ToolBarData(icon, clearGroup));
        clearAction.setDescription("Clear profile");
        addAction(clearAction);

        refreshAction = new DockingAction("Refresh User Profiles", plugin.getName())
        {
            @Override
            public void actionPerformed(ActionContext context) { refresh(); }

            @Override
            public boolean isEnabledForContext(ActionContext context) { return true; }
        };
        icon = Icons.REFRESH_ICON;
        String refreshGroup = "refresh";
        refreshAction.setMenuBarData(new MenuData(new String[]{"Refresh"}, icon, refreshGroup));
        refreshAction.setToolBarData(new ToolBarData(icon, refreshGroup));
        refreshAction.setDescription("Refresh list of user profiles");
        addAction(refreshAction);

        deleteAction = new DockingAction("Delete Profile", plugin.getName())
        {
            @Override
            public void actionPerformed(ActionContext context) { delete(); }

            @Override
            public boolean isEnabledForContext(ActionContext context)
            {
                ComboBoxItem item = (ComboBoxItem) comboBox.getSelectedItem();
                return item != null && item.isUserDefined;
            }
        };
        icon = Icons.DELETE_ICON;
        String deleteGroup = "Xdelete";
        deleteAction.setMenuBarData(new MenuData(new String[]{"Delete"}, icon, deleteGroup));
        deleteAction.setToolBarData(new ToolBarData(icon, deleteGroup));
        deleteAction.setDescription("Delete profile");
        addAction(deleteAction);
    }

    private void refresh()
    {
        ComboBoxItem item = (ComboBoxItem) comboBox.getSelectedItem();
        if (item != null && item.isChanged)
            processItemChanged(item);
        comboBox.removeItemListener(comboItemListener);
        itemList.clear();
        comboModel.removeAllElements();
        populateComboBox();
        comboBox.addItemListener(comboItemListener);
        if (itemList.contains(item))
            comboBox.setSelectedItem(item);
        else
            loadProfile();
    }

    private void clear()
    {
        pathPanel.clear();
        includePathPanel.clear();
        parseOptionsField.setText("");
        ComboBoxItem item = (ComboBoxItem) comboBox.getSelectedItem();
        if (item != null)
            item.isChanged = true;
    }

    private void save(ComboBoxItem item)
    {
        if (!item.isUserDefined)
        {
            saveAs(item);
        }
        else
        {
            writeProfile(item.file);
            item.isChanged = false;
            notifyContextChanged();
        }
    }

    private void saveAs(ComboBoxItem item)
    {
        InputDialog d = new InputDialog("Enter Profile Name", "Profile Name");
        plugin.getTool().showDialog(d, getComponent());

        String name = d.getValue();
        if (name != null && !name.isEmpty())
        {
            if (!name.endsWith(FILE_EXTENSION))
                name = name + FILE_EXTENSION;

            ResourceFile file = new ResourceFile(parentUserFile, name);
            if (file.equals(item.file))
            {
                save(item);
                return;
            }

            if (file.exists())
            {
                if (OptionDialog.showOptionDialog(rootPanel, "Overwrite Existing File?",
                    "The file " + file.getAbsolutePath() +
                        " already exists.\nDo you want to overwrite it?",
                    "Yes", OptionDialog.QUESTION_MESSAGE) != OptionDialog.OPTION_ONE)
                {
                    return;
                }
                file.delete();
            }

            saveAsInProgress = true;
            ComboBoxItem newItem = new ComboBoxItem(file, true);
            if (itemList.contains(newItem))
            {
                itemList.remove(newItem);
                comboModel.removeElement(newItem);
            }
            int index = Collections.binarySearch(itemList, newItem, comparator);
            if (index < 0)
                index = -index - 1;

            itemList.add(index, newItem);
            writeProfile(newItem.file);
            newItem.isChanged = false;
            item.isChanged = false;
            try
            {
                comboModel.insertElementAt(newItem, index);
                comboBox.setSelectedIndex(index);
            }
            finally
            {
                saveAsInProgress = false;
            }
            notifyContextChanged();
        }
    }

    private void loadProfile()
    {
        ComboBoxItem item = (ComboBoxItem) comboBox.getSelectedItem();
        if (item == null)
            return;

        if (docListener != null)
            parseOptionsField.getDocument().removeDocumentListener(docListener);
        tableModel.removeTableModelListener(tableListener);
        parsePathTableModel.removeTableModelListener(parsePathTableListener);

        item.isChanged = false;

        StringBuilder sb = new StringBuilder();
        List<String> pathList = new ArrayList<>();
        List<String> includeList = new ArrayList<>();
        String langString = null;
        String compileString = null;

        try
        {
            BufferedReader br = new BufferedReader(new InputStreamReader(item.file.getInputStream()));
            String line;

            // Section 1: Source files
            while ((line = br.readLine()) != null && !line.trim().isEmpty())
                pathList.add(line.trim());

            // Section 2: Parse options
            while ((line = br.readLine()) != null && !line.trim().isEmpty())
                sb.append(line.trim()).append("\n");

            // Section 3: Include paths
            while ((line = br.readLine()) != null && !line.trim().isEmpty())
                includeList.add(line.trim());

            // Section 4: Language ID
            while ((line = br.readLine()) != null)
            {
                line = line.trim();
                if (!line.isEmpty())
                {
                    langString = line;
                    break;
                }
            }

            // Section 5: Compiler spec ID
            while ((line = br.readLine()) != null)
            {
                line = line.trim();
                if (!line.isEmpty())
                {
                    compileString = line;
                    break;
                }
            }

            br.close();

            pathPanel.setPaths(pathList.toArray(new String[0]));
            includePathPanel.setPaths(includeList.toArray(new String[0]));
            parseOptionsField.setText(sb.toString());
            languageIDString = langString;
            compilerIDString = compileString;
            updateArchitectureDescription();
        }
        catch (FileNotFoundException e)
        {
            Msg.showInfo(getClass(), getComponent(), "File Not Found",
                "Could not find file\n" + item.file.getAbsolutePath());
        }
        catch (IOException e)
        {
            Msg.showError(this, getComponent(), "Error Loading Profile",
                "Exception occurred while reading file\n" + item.file.getAbsolutePath() + ": " + e);
        }
        finally
        {
            addDocumentListener();
            tableModel.addTableModelListener(tableListener);
            parsePathTableModel.addTableModelListener(parsePathTableListener);
            notifyContextChanged();
        }
    }

    private void writeProfile(ResourceFile outputFile)
    {
        try
        {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputFile.getOutputStream()));

            // Section 1: Source files
            for (String path : pathPanel.getPaths())
            {
                writer.write(path.trim());
                writer.newLine();
            }
            writer.newLine();

            // Section 2: Parse options
            String optStr = parseOptionsField.getText();
            StringTokenizer st = new StringTokenizer(optStr, "\n");
            while (st.hasMoreTokens())
            {
                writer.write(st.nextToken());
                writer.newLine();
            }
            writer.newLine();

            // Section 3: Include paths
            for (String path : includePathPanel.getPaths())
            {
                writer.write(path.trim());
                writer.newLine();
            }
            writer.newLine();

            // Section 4: Language ID
            if (languageIDString != null)
                writer.write(languageIDString);
            writer.newLine();
            writer.newLine();

            // Section 5: Compiler spec ID
            if (compilerIDString != null)
                writer.write(compilerIDString);
            writer.newLine();
            writer.newLine();

            writer.close();
        }
        catch (IOException e)
        {
            Msg.showError(this, getComponent(), "Error Writing Profile",
                "Writing profile " + outputFile.getName() + " failed", e);
        }
    }

    private void delete()
    {
        ComboBoxItem item = (ComboBoxItem) comboBox.getSelectedItem();
        if (item.isUserDefined)
        {
            if (OptionDialog.showOptionDialog(getComponent(), "Delete Profile?",
                "Are you sure you want to delete profile " + item.getName(), "Delete",
                OptionDialog.QUESTION_MESSAGE) == OptionDialog.OPTION_ONE)
            {
                item.file.delete();
                itemList.remove(item);
                comboModel.removeElement(item);
            }
        }
    }

    private void doParse(boolean parseToFile)
    {
        clearStatusText();
        String options = parseOptionsField.getText();
        String[] includePaths = includePathPanel.getPaths();
        String[] paths = pathPanel.getPaths();

        if (paths.length == 0)
        {
            Msg.showInfo(getClass(), rootPanel, "Source Files Not Specified",
                "Please specify source files to parse.");
            return;
        }

        if (parseToFile)
        {
            if (languageIDString == null || compilerIDString == null)
            {
                Msg.showWarn(getClass(), rootPanel, "Program Architecture not Specified",
                    "A Program Architecture must be specified in order to parse to a file.");
                return;
            }

            File file = getSaveFile();
            if (file != null)
            {
                plugin.parse(paths, includePaths, options, languageIDString, compilerIDString,
                    file.getAbsolutePath());
            }
            return;
        }

        plugin.parse(paths, includePaths, options);
    }

    private void populateComboBox()
    {
        // Find built-in profiles from Ghidra's Base module (not our extension module)
        ResourceFile systemProfileDir = findSystemProfileDir();
        if (systemProfileDir != null)
            addToComboModel(systemProfileDir, false);

        parentUserFile = new ResourceFile(plugin.getUserProfileDir());
        addToComboModel(parentUserFile, true);
    }

    /**
     * Searches Ghidra application root directories for the Base module's
     * parserprofiles data directory. We can't use Application.getModuleDataSubDirectory()
     * because that only searches the current extension module.
     */
    private ResourceFile findSystemProfileDir()
    {
        for (ResourceFile root : Application.getApplicationRootDirectories())
        {
            ResourceFile profileDir = new ResourceFile(root, "Features/Base/data/" + PROFILE_DIR);
            if (profileDir.isDirectory())
                return profileDir;
        }
        // Fallback: try the current module in case profiles are bundled with the extension
        try
        {
            return Application.getModuleDataSubDirectory(PROFILE_DIR);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    private void addToComboModel(ResourceFile parent, boolean isUserDefined)
    {
        ResourceFile[] children = parent.listFiles();
        if (children == null)
            return;

        List<ResourceFile> sorted = Arrays.asList(children);
        Collections.sort(sorted);
        for (ResourceFile resourceFile : sorted)
        {
            if (resourceFile.getName().startsWith("."))
                continue;
            ComboBoxItem item = new ComboBoxItem(resourceFile, isUserDefined);
            comboModel.addElement(item);
            itemList.add(item);
        }
    }

    private File getSaveFile()
    {
        GhidraFileChooser fileChooser = new GhidraFileChooser(rootPanel);
        fileChooser.setTitle("Choose Save Archive File");
        fileChooser.setApproveButtonText("Choose Save Archive File");
        fileChooser.setApproveButtonToolTipText("Choose filename for archive");
        fileChooser.setLastDirectoryPreference(Preferences.LAST_EXPORT_DIRECTORY);

        File file = fileChooser.getSelectedFile();
        fileChooser.dispose();
        if (file == null)
            return null;

        File parent = file.getParentFile();
        if (parent != null)
            Preferences.setProperty(Preferences.LAST_EXPORT_DIRECTORY, parent.getAbsolutePath());

        String name = file.getName();
        if (!name.endsWith(FileDataTypeManager.SUFFIX))
            file = new File(file.getParentFile(), name + FileDataTypeManager.SUFFIX);

        if (!file.exists())
            return file;

        int choice = OptionDialog.showOptionDialog(rootPanel, "Overwrite Existing File?",
            "The file " + file.getAbsolutePath() +
                " already exists.\nDo you want to overwrite it?",
            "Yes", OptionDialog.QUESTION_MESSAGE);

        if (choice != OptionDialog.OPTION_ONE)
            return null;

        try
        {
            PackedDatabase.delete(file);
        }
        catch (IOException e)
        {
            Msg.showError(this, mainPanel, "Archive Overwrite Failed", e.getMessage());
            return null;
        }
        return file;
    }

    @Override
    protected void dismissCallback()
    {
        close();
    }

    @Override
    public void close()
    {
        cancelCurrentTask();
        super.close();
    }

    // Inner classes

    static class ComboBoxItem
    {
        final ResourceFile file;
        final boolean isUserDefined;
        boolean isChanged;

        ComboBoxItem(ResourceFile file, boolean isUserDefined)
        {
            this.file = file;
            this.isUserDefined = isUserDefined;
        }

        @Override
        public String toString()
        {
            return file.getName() + (isUserDefined ? "" : " (Default)");
        }

        public String getName()
        {
            return file.getName();
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            ComboBoxItem item = (ComboBoxItem) obj;
            return file.equals(item.file) && isUserDefined == item.isUserDefined;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(file, isUserDefined);
        }
    }

    private static class ComboBoxItemComparator implements Comparator<ComboBoxItem>
    {
        @Override
        public int compare(ComboBoxItem item1, ComboBoxItem item2)
        {
            if (item1.isUserDefined == item2.isUserDefined)
                return item1.getName().compareToIgnoreCase(item2.getName());
            return item1.isUserDefined ? 1 : -1;
        }
    }
}
