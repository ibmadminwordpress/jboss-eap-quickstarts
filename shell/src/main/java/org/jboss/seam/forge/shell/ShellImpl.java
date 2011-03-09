/*
 * JBoss, by Red Hat.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.seam.forge.shell;

import static org.mvel2.DataConversion.addConversionHandler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import jline.console.ConsoleReader;
import jline.console.completer.AggregateCompleter;
import jline.console.completer.Completer;
import jline.console.completer.FileNameCompleter;
import jline.console.history.MemoryHistory;

import org.fusesource.jansi.Ansi;
import org.jboss.seam.forge.project.Project;
import org.jboss.seam.forge.project.dependencies.Dependency;
import org.jboss.seam.forge.project.facets.JavaSourceFacet;
import org.jboss.seam.forge.project.services.ResourceFactory;
import org.jboss.seam.forge.resources.DirectoryResource;
import org.jboss.seam.forge.resources.FileResource;
import org.jboss.seam.forge.resources.Resource;
import org.jboss.seam.forge.resources.java.JavaResource;
import org.jboss.seam.forge.shell.command.PromptTypeConverter;
import org.jboss.seam.forge.shell.command.convert.BooleanConverter;
import org.jboss.seam.forge.shell.command.convert.DependencyIdConverter;
import org.jboss.seam.forge.shell.command.convert.FileConverter;
import org.jboss.seam.forge.shell.command.fshparser.FSHRuntime;
import org.jboss.seam.forge.shell.completer.CompletedCommandHolder;
import org.jboss.seam.forge.shell.completer.OptionAwareCompletionHandler;
import org.jboss.seam.forge.shell.completer.PluginCommandCompleter;
import org.jboss.seam.forge.shell.events.AcceptUserInput;
import org.jboss.seam.forge.shell.events.PostStartup;
import org.jboss.seam.forge.shell.events.PreShutdown;
import org.jboss.seam.forge.shell.events.Shutdown;
import org.jboss.seam.forge.shell.events.Startup;
import org.jboss.seam.forge.shell.exceptions.AbortedException;
import org.jboss.seam.forge.shell.exceptions.CommandExecutionException;
import org.jboss.seam.forge.shell.exceptions.CommandParserException;
import org.jboss.seam.forge.shell.exceptions.PluginExecutionException;
import org.jboss.seam.forge.shell.exceptions.ShellExecutionException;
import org.jboss.seam.forge.shell.plugins.builtin.Echo;
import org.jboss.seam.forge.shell.project.CurrentProject;
import org.jboss.seam.forge.shell.util.Files;
import org.jboss.seam.forge.shell.util.GeneralUtils;
import org.jboss.seam.forge.shell.util.JavaPathspecParser;
import org.jboss.seam.forge.shell.util.OSUtils;
import org.jboss.seam.forge.shell.util.ResourceUtil;
import org.jboss.weld.environment.se.bindings.Parameters;
import org.mvel2.ConversionHandler;
import org.mvel2.DataConversion;
import org.mvel2.util.StringAppender;

import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 */
@ApplicationScoped
@SuppressWarnings("restriction")
public class ShellImpl implements Shell
{
   private static final String PROP_PROMPT = "PROMPT";
   private static final String PROP_PROMPT_NO_PROJ = "PROMPT_NOPROJ";

   private static final String DEFAULT_PROMPT = "[\\c{green}$PROJECT_NAME\\c] \\c{white}\\W\\c \\c{green}\\$\\c ";
   private static final String DEFAULT_PROMPT_NO_PROJ = "[\\c{red}no project\\c] \\c{white}\\W\\c \\c{red}\\$\\c ";

   private static final String PROP_DEFAULT_PLUGIN_REPO = "DEFFAULT_PLUGIN_REPO";
   private static final String DEFAULT_PLUGIN_REPO = "http://seamframework.org/service/File/148617";

   private static final String PROP_VERBOSE = "VERBOSE";

   public static final String FORGE_CONFIG_DIR = System.getProperty("user.home") + "/.forge/";
   public static final String FORGE_COMMAND_HISTORY_FILE = "cmd_history";
   public static final String FORGE_CONFIG_FILE = "config";

   private final Map<String, Object> properties = new HashMap<String, Object>();

   @Inject
   @Parameters
   private List<String> parameters;

   @Inject
   private Event<PostStartup> postStartup;

   @Inject
   private CurrentProject projectContext;

   @Inject
   private ResourceFactory resourceFactory;
   private Resource<?> lastResource;

   @Inject
   private FSHRuntime fshRuntime;

   @Inject
   private PromptTypeConverter promptTypeConverter;

   @Inject
   private CompletedCommandHolder commandHolder;

   private ConsoleReader reader;
   private Completer completer;

   private boolean pretend = false;
   private boolean exitRequested = false;

   private InputStream inputStream;
   private Writer outputWriter;

   private OutputStream historyOutstream;

   private final boolean colorEnabled = Boolean.getBoolean("seam.forge.shell.colorEnabled");

   private final ConversionHandler resourceConversionHandler = new ConversionHandler()
   {
      @Override
      @SuppressWarnings("rawtypes")
      public Resource[] convertFrom(final Object obl)
      {
         return GeneralUtils.parseSystemPathspec(resourceFactory, lastResource, getCurrentResource(),
                  obl instanceof String[] ? (String[]) obl : new String[] { obl.toString() });
      }

      @SuppressWarnings("rawtypes")
      @Override
      public boolean canConvertFrom(final Class aClass)
      {
         return true;
      }
   };

   private final ConversionHandler javaResourceConversionHandler = new ConversionHandler()
   {
      @Override
      public JavaResource[] convertFrom(final Object obj)
      {
         if (getCurrentProject().hasFacet(JavaSourceFacet.class))
         {
            String[] strings = obj instanceof String[] ? (String[]) obj : new String[] { obj.toString() };
            List<Resource<?>> resources = new ArrayList<Resource<?>>();
            for (String string : strings)
            {
               resources.addAll(new JavaPathspecParser(getCurrentProject().getFacet(JavaSourceFacet.class),
                        string).resolve());
            }

            List<JavaResource> filtered = new ArrayList<JavaResource>();
            for (Resource<?> resource : resources)
            {
               if (resource instanceof JavaResource)
               {
                  filtered.add((JavaResource) resource);
               }
            }

            JavaResource[] result = new JavaResource[filtered.size()];
            result = filtered.toArray(result);
            return result;
         }
         else
            return null;
      }

      @SuppressWarnings("rawtypes")
      @Override
      public boolean canConvertFrom(final Class aClass)
      {
         return true;
      }
   };
   private boolean exitOnNextSignal = false;

   void init(@Observes final Startup event, final PluginCommandCompleter pluginCompleter) throws Exception
   {
      BooleanConverter booleanConverter = new BooleanConverter();

      addConversionHandler(boolean.class, booleanConverter);
      addConversionHandler(Boolean.class, booleanConverter);
      addConversionHandler(File.class, new FileConverter());
      addConversionHandler(Dependency.class, new DependencyIdConverter());

      addConversionHandler(JavaResource[].class, javaResourceConversionHandler);
      addConversionHandler(JavaResource.class, new ConversionHandler()
      {

         @Override
         public Object convertFrom(final Object obj)
         {
            JavaResource[] res = (JavaResource[]) javaResourceConversionHandler.convertFrom(obj);
            if (res.length > 1)
            {
               throw new RuntimeException("ambiguous paths");
            }
            else if (res.length == 0)
            {
               if (getCurrentProject().hasFacet(JavaSourceFacet.class))
               {
                  JavaSourceFacet java = getCurrentProject().getFacet(JavaSourceFacet.class);
                  try
                  {
                     JavaResource resource = java.getJavaResource(obj.toString());
                     return resource;
                  }
                  catch (FileNotFoundException e)
                  {
                     throw new RuntimeException(e);
                  }
               }
               return null;
            }
            else
            {
               return res[0];
            }
         }

         @Override
         @SuppressWarnings("rawtypes")
         public boolean canConvertFrom(final Class type)
         {
            return javaResourceConversionHandler.canConvertFrom(type);
         }
      });
      addConversionHandler(Resource[].class, resourceConversionHandler);
      addConversionHandler(Resource.class, new ConversionHandler()

      {
         @Override
         public Object convertFrom(final Object o)
         {
            Resource<?>[] res = (Resource<?>[]) resourceConversionHandler.convertFrom(o);
            if (res.length > 1)
            {
               throw new RuntimeException("ambiguous paths");
            }
            else if (res.length == 0)
            {
               return ResourceUtil.parsePathspec(resourceFactory, getCurrentResource(), o.toString()).get(0);
            }
            else
            {
               return res[0];
            }
         }

         @Override
         @SuppressWarnings("rawtypes")
         public boolean canConvertFrom(final Class aClass)
         {
            return resourceConversionHandler.canConvertFrom(aClass);
         }
      });

      projectContext.setCurrentResource(resourceFactory.getResourceFrom(event.getWorkingDirectory()));
      properties.put("CWD", getCurrentDirectory().getFullyQualifiedName());

      initStreams();
      initCompleters(pluginCompleter);
      initParameters();
      initSignalHandlers();

      if (event.isRestart())
      {
         // suppress the MOTD if this is a restart.
         properties.put("NO_MOTD", true);
      }
      else
      {
         properties.put("NO_MOTD", false);
      }

      properties.put("OS_NAME", OSUtils.getOsName());
      properties.put("FORGE_CONFIG_DIR", FORGE_CONFIG_DIR);
      properties.put(PROP_PROMPT, "> ");
      properties.put(PROP_PROMPT_NO_PROJ, "> ");
      loadConfig();

      postStartup.fire(new PostStartup());
   }

   private static void initSignalHandlers()
   {
      try
      {
         // check to see if we have something to work with.
         Class.forName("sun.misc.SignalHandler");

         SignalHandler signalHandler = new SignalHandler()
         {
            @Override
            public void handle(final Signal signal)
            {
               // TODO implement smart shutdown (if they keep pressing CTRL-C)
            }
         };

         Signal.handle(new Signal("INT"), signalHandler);
      }
      catch (ClassNotFoundException e)
      {
         // signal trapping not supported. Oh well, switch to a Sun-based JVM, loser!
      }
   }

   private void loadConfig()
   {
      File configDir = new File(FORGE_CONFIG_DIR);

      if (!configDir.exists())
      {
         if (!configDir.mkdirs())
         {
            System.err.println("could not create config directory: " + configDir.getAbsolutePath());
            return;
         }
      }

      File historyFile = new File(configDir.getPath() + "/" + FORGE_COMMAND_HISTORY_FILE);

      try
      {
         if (!historyFile.exists())
         {
            if (!historyFile.createNewFile())
            {
               System.err.println("could not create config file: " + historyFile.getAbsolutePath());
            }

         }
      }
      catch (IOException e)
      {
         throw new RuntimeException("could not create config file: " + historyFile.getAbsolutePath());
      }

      MemoryHistory history = new MemoryHistory();
      try
      {
         StringAppender buf = new StringAppender();
         InputStream instream = new BufferedInputStream(new FileInputStream(historyFile));

         byte[] b = new byte[25];
         int read;

         while ((read = instream.read(b)) != -1)
         {
            for (int i = 0; i < read; i++)
            {
               if (b[i] == '\n')
               {
                  history.add(buf.toString());
                  buf.reset();
               }
               else
               {
                  buf.append(b[i]);
               }
            }
         }

         instream.close();

         reader.setHistory(history);
      }
      catch (IOException e)
      {
         throw new RuntimeException("error loading file: " + historyFile.getAbsolutePath());
      }

      File configFile = new File(configDir.getPath() + "/" + FORGE_CONFIG_FILE);

      if (!configFile.exists())
      {
         try
         {
            /**
             * Create a default config file.
             */

            configFile.createNewFile();
            OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(configFile));
            String defaultConfig = getDefaultConfig();
            for (int i = 0; i < defaultConfig.length(); i++)
            {
               outputStream.write(defaultConfig.charAt(i));
            }
            outputStream.flush();
            outputStream.close();

         }
         catch (IOException e)
         {
            e.printStackTrace();
            throw new RuntimeException("error loading file: " + historyFile.getAbsolutePath());
         }
      }

      try
      {
         /**
          * Load the config file script.
          */
         execute(configFile);
      }
      catch (IOException e)
      {
         e.printStackTrace();
         throw new RuntimeException("error loading file: " + historyFile.getAbsolutePath());
      }

      try
      {
         historyOutstream = new BufferedOutputStream(new FileOutputStream(historyFile, true));

         Runtime.getRuntime().addShutdownHook(new Thread()
         {
            @Override
            public void run()
            {
               try
               {
                  historyOutstream.flush();
                  historyOutstream.close();
               }
               catch (Exception e)
               {
               }
            }
         });
      }
      catch (IOException e)
      {
      }
   }

   private void writeToHistory(final String command)
   {
      try
      {
         for (int i = 0; i < command.length(); i++)
         {
            historyOutstream.write(command.charAt(i));
         }
         historyOutstream.write('\n');
      }
      catch (IOException e)
      {
      }
   }

   private void initCompleters(final PluginCommandCompleter pluginCompleter)
   {
      List<Completer> completers = new ArrayList<Completer>();
      completers.add(pluginCompleter);

      completer = new AggregateCompleter(completers);
      this.reader.addCompleter(completer);
      this.reader.setCompletionHandler(new OptionAwareCompletionHandler(commandHolder, this));
   }

   private void initStreams() throws IOException
   {
      if (inputStream == null)
      {
         inputStream = System.in;
      }
      if (outputWriter == null)
      {
         outputWriter = new PrintWriter(System.out);
      }
      this.reader = new ConsoleReader(inputStream, outputWriter);
      this.reader.setHistoryEnabled(true);
      this.reader.setBellEnabled(false);
   }

   private void initParameters()
   {
      properties.put(PROP_VERBOSE, String.valueOf(parameters.contains("--verbose")));

      if (parameters.contains("--pretend"))
      {
         pretend = true;
      }

      if ((parameters != null) && !parameters.isEmpty())
      {
         // this is where we will initialize other parameters... e.g. accepting
         // a path
      }
   }

   private String getDefaultConfig()
   {
      return "@/* Automatically generated config file */;\n"
               +
               "if (!$NO_MOTD) { "
               +
               "   echo \"   ____                          _____                    \";\n"
               +
               "   echo \"  / ___|  ___  __ _ _ __ ___    |  ___|__  _ __ __ _  ___ \";\n"
               +
               "   echo \"  \\\\___ \\\\ / _ \\\\/ _` | '_ ` _ \\\\   | |_ / _ \\\\| '__/ _` |/ _ \\\\  \\c{yellow}\\\\\\\\\\c\";\n"
               +
               "   echo \"   ___) |  __/ (_| | | | | | |  |  _| (_) | | | (_| |  __/  \\c{yellow}//\\c\";\n" +
               "   echo \"  |____/ \\\\___|\\\\__,_|_| |_| |_|  |_|  \\\\___/|_|  \\\\__, |\\\\___| \";\n" +
               "   echo \"                                                |___/      \";\n\n" +
               "}\n" +
               "\n" +
               "if ($OS_NAME.startsWith(\"Windows\")) {\n" +
               "    echo \"  Windows? Really? Okay...\\n\"\n" +
               "}\n" +
               "\n" +
               "set " + PROP_PROMPT + " \"" + DEFAULT_PROMPT + "\";\n" +
               "set " + PROP_PROMPT_NO_PROJ + " \"" + DEFAULT_PROMPT_NO_PROJ + "\";\n" +
               "set " + PROP_DEFAULT_PLUGIN_REPO + " \"" + DEFAULT_PLUGIN_REPO + "\"\n";

   }

   void teardown(@Observes final Shutdown shutdown, final Event<PreShutdown> preShutdown)
   {
      preShutdown.fire(new PreShutdown(shutdown.getStatus()));
      exitRequested = true;
   }

   void doShell(@Observes final AcceptUserInput event)
   {
      String line;
      reader.setPrompt(getPrompt());
      while (!exitRequested)
      {
         try
         {
            line = readLineShell();

            if (line != null)
            {
               if (!"".equals(line.trim()))
               {
                  writeToHistory(line);
                  execute(line);
               }
               reader.setPrompt(getPrompt());
            }
         }
         catch (Exception e)
         {
            handleException(e);
         }
      }
      println();
   }

   private void handleException(final Exception original)
   {
      try
      {
         // unwrap any aborted exceptions
         Throwable cause = original;
         while (cause != null)
         {
            if (cause instanceof AbortedException)
               throw (AbortedException) cause;

            cause = cause.getCause();
         }

         throw original;
      }
      catch (AbortedException e)
      {
         ShellMessages.info(this, "Aborted.");
         if (isVerbose())
         {
            e.printStackTrace();
         }
      }
      catch (CommandExecutionException e)
      {
         ShellMessages.error(this, formatSourcedError(e.getCommand()) + e.getMessage());
         if (isVerbose())
         {
            e.printStackTrace();
         }
      }
      catch (CommandParserException e)
      {
         ShellMessages.error(this, "[" + formatSourcedError(e.getCommand()) + "] " + e.getMessage());
         if (isVerbose())
         {
            e.printStackTrace();
         }
      }
      catch (PluginExecutionException e)
      {
         ShellMessages.error(this, "[" + formatSourcedError(e.getPlugin()) + "] " + e.getMessage());
         if (isVerbose())
         {
            e.printStackTrace();
         }
      }
      catch (ShellExecutionException e)
      {
         ShellMessages.error(this, e.getMessage());
         if (isVerbose())
         {
            e.printStackTrace();
         }
      }
      catch (Exception e)
      {
         if (!isVerbose())
         {
            ShellMessages.error(this, "Exception encountered: " + e.getMessage()
                     + " (type \"set VERBOSE true\" to enable stack traces)");
         }
         else
         {
            ShellMessages.error(this, "Exception encountered: (type \"set VERBOSE false\" to disable stack traces)");
            e.printStackTrace();
         }
      }
   }

   private String formatSourcedError(final Object obj)
   {
      return (obj == null ? "" : ("[" + obj.toString() + "] "));
   }

   @Override
   public String readLine() throws IOException
   {
      String line = reader.readLine();

      if (line == null)
      {
         reader.println();
         reader.flush();
         throw new AbortedException();
      }

      exitOnNextSignal = false;
      return line;
   }

   private String readLineShell() throws IOException
   {
      String line = reader.readLine();
      if (line == null)
      {
         if (this.exitOnNextSignal == false)
         {
            println();
            println("(Press CTRL-D again or type 'exit' to quit.)");
            this.exitOnNextSignal = true;
         }
         else
         {
            print("exit");
            this.exitRequested = true;
         }
         reader.flush();
      }
      else
      {
         exitOnNextSignal = false;
      }
      return line;
   }

   @Override
   public int scan()
   {
      try
      {
         return reader.readVirtualKey();
      }
      catch (IOException e)
      {
         return -1;
      }
   }

   @Override
   public void clearLine()
   {
      print(new Ansi().eraseLine(Ansi.Erase.ALL).toString());
   }

   @Override
   public void cursorLeft(final int x)
   {
      print(new Ansi().cursorLeft(x).toString());
   }

   @Override
   public void execute(final String line)
   {
      try
      {
         fshRuntime.run(line);
      }
      catch (Exception e)
      {
         handleException(e);
      }
   }

   @Override
   public void execute(final File file) throws IOException
   {
      StringBuilder buf = new StringBuilder();
      InputStream instream = new BufferedInputStream(new FileInputStream(file));
      try
      {
         byte[] b = new byte[25];
         int read;

         while ((read = instream.read(b)) != -1)
         {
            for (int i = 0; i < read; i++)
            {
               buf.append((char) b[i]);
            }
         }

         instream.close();

         execute(buf.toString());
      }
      finally
      {
         instream.close();
      }
   }

   @Override
   public void execute(final File file, final String... args) throws IOException
   {
      StringBuilder buf = new StringBuilder();

      String funcName = file.getName().replaceAll("\\.", "_") + "_" + String.valueOf(hashCode()).replaceAll("\\-", "M");

      buf.append("def ").append(funcName).append('(');
      if (args != null)
      {
         for (int i = 0; i < args.length; i++)
         {
            buf.append("_").append(String.valueOf(i));
            if (i + 1 < args.length)
            {
               buf.append(", ");
            }
         }
      }

      buf.append(") {\n");

      if (args != null)
      {
         buf.append("@_vararg = new String[").append(args.length).append("];\n");

         for (int i = 0; i < args.length; i++)
         {
            buf.append("@_vararg[").append(String.valueOf(i)).append("] = ")
                     .append("_").append(String.valueOf(i)).append(";\n");
         }
      }

      InputStream instream = new BufferedInputStream(new FileInputStream(file));
      try
      {
         byte[] b = new byte[25];
         int read;

         while ((read = instream.read(b)) != -1)
         {
            for (int i = 0; i < read; i++)
            {
               buf.append((char) b[i]);
            }
         }

         buf.append("\n}; \n@").append(funcName).append('(');

         if (args != null)
         {
            for (int i = 0; i < args.length; i++)
            {
               buf.append("\"").append(args[i].replaceAll("\\\"", "\\\\\\\"")).append("\"");
               if (i + 1 < args.length)
               {
                  buf.append(", ");
               }
            }
         }

         buf.append(");\n");

         // System.out.println("\nexec:" + buf.toString());

         execute(buf.toString());
      }
      finally
      {
         properties.remove(funcName);
         instream.close();
      }
   }

   /*
    * Shell Print Methods
    */
   @Override
   public void printlnVerbose(final String line)
   {
      if (isVerbose())
      {
         System.out.println(line);
      }
   }

   @Override
   public void print(final String output)
   {
      System.out.print(output);
   }

   @Override
   public void println(final String output)
   {
      System.out.println(output);
   }

   @Override
   public void println()
   {
      System.out.println();
   }

   @Override
   public void print(final ShellColor color, final String output)
   {
      print(renderColor(color, output));
   }

   @Override
   public void println(final ShellColor color, final String output)
   {
      println(renderColor(color, output));
   }

   @Override
   public void printlnVerbose(final ShellColor color, final String output)
   {
      printlnVerbose(renderColor(color, output));
   }

   @Override
   public String renderColor(final ShellColor color, final String output)
   {
      if (!colorEnabled)
      {
         return output;
      }

      Ansi ansi = new Ansi();

      switch (color)
      {
      case BLACK:
         ansi.fg(Ansi.Color.BLACK);
         break;
      case BLUE:
         ansi.fg(Ansi.Color.BLUE);
         break;
      case CYAN:
         ansi.fg(Ansi.Color.CYAN);
         break;
      case GREEN:
         ansi.fg(Ansi.Color.GREEN);
         break;
      case MAGENTA:
         ansi.fg(Ansi.Color.MAGENTA);
         break;
      case RED:
         ansi.fg(Ansi.Color.RED);
         break;
      case WHITE:
         ansi.fg(Ansi.Color.WHITE);
         break;
      case YELLOW:
         ansi.fg(Ansi.Color.YELLOW);
         break;
      case BOLD:
         ansi.a(Ansi.Attribute.INTENSITY_BOLD);
         break;

      default:
         ansi.fg(Ansi.Color.WHITE);
      }

      return ansi.render(output).reset().toString();
   }

   @Override
   public void write(final byte b)
   {
      System.out.print((char) b);
   }

   @Override
   public void clear()
   {
      print(new Ansi().cursor(0, 0).eraseScreen().toString());
   }

   @Override
   public boolean isVerbose()
   {
      Object s = properties.get(PROP_VERBOSE);
      return (s != null) && "true".equals(s);
   }

   @Override
   public void setVerbose(final boolean verbose)
   {
      properties.put(PROP_VERBOSE, String.valueOf(verbose));
   }

   @Override
   public boolean isPretend()
   {
      return pretend;
   }

   @Override
   public void setInputStream(final InputStream is) throws IOException
   {
      this.inputStream = is;
      initStreams();
   }

   @Override
   public void setOutputWriter(final Writer os) throws IOException
   {
      this.outputWriter = os;
      initStreams();
   }

   @Override
   public void setProperty(final String name, final Object value)
   {
      properties.put(name, value);
   }

   @Override
   public Object getProperty(final String name)
   {
      return properties.get(name);
   }

   @Override
   public Map<String, Object> getProperties()
   {
      return properties;
   }

   @Override
   public void setDefaultPrompt()
   {
      setPrompt("");
   }

   @Override
   public void setPrompt(final String prompt)
   {
      setProperty(PROP_PROMPT, prompt);
   }

   @Override
   public String getPrompt()
   {
      if (projectContext.getCurrent() != null)
      {
         return Echo.echo(this, Echo.promptExpressionParser(this, (String) properties.get(PROP_PROMPT)));
      }
      else
      {
         return Echo.echo(this, Echo.promptExpressionParser(this, (String) properties.get(PROP_PROMPT_NO_PROJ)));
      }
   }

   @Override
   public DirectoryResource getCurrentDirectory()
   {
      Resource<?> r = getCurrentResource();
      return ResourceUtil.getContextDirectory(r);
   }

   @Override
   public Resource<?> getCurrentResource()
   {
      Resource<?> result = this.projectContext.getCurrentResource();
      if (result == null)
      {
         result = this.resourceFactory.getResourceFrom(Files.getWorkingDirectory());
         properties.put("CWD", result.getFullyQualifiedName());
      }

      return result;
   }

   @Override
   @SuppressWarnings("unchecked")
   public Class<? extends Resource<?>> getCurrentResourceScope()
   {
      return (Class<? extends Resource<?>>) getCurrentResource().getClass();
   }

   @Override
   public void setCurrentResource(final Resource<?> resource)
   {
      lastResource = getCurrentResource();
      projectContext.setCurrentResource(resource);
      properties.put("CWD", resource.getFullyQualifiedName());
   }

   @Override
   public Project getCurrentProject()
   {
      return this.projectContext.getCurrent();
   }

   /*
    * Shell Prompts
    */
   @Override
   public String prompt()
   {
      return prompt("");
   }

   @Override
   public String promptAndSwallowCR()
   {
      int c;
      StringBuilder buf = new StringBuilder();
      while (((c = scan()) != '\n') && (c != '\r'))
      {
         if (c == 127)
         {
            if (buf.length() > 0)
            {
               buf.deleteCharAt(buf.length() - 1);
               cursorLeft(1);
               print(" ");
               cursorLeft(1);
            }
            continue;
         }

         write((byte) c);
         buf.append((char) c);
      }
      return buf.toString();
   }

   @Override
   public String prompt(final String message)
   {
      return promptWithCompleter(message, null);
   }

   private String promptWithCompleter(String message, final Completer tempCompleter)
   {
      if (!message.isEmpty() && message.matches("^.*\\S$"))
      {
         message = message + " ";
      }

      try
      {
         reader.removeCompleter(this.completer);
         if (tempCompleter != null)
         {
            reader.addCompleter(tempCompleter);
         }
         reader.setHistoryEnabled(false);
         reader.setPrompt(message);
         String line = readLine();
         return line;
      }
      catch (IOException e)
      {
         throw new IllegalStateException("Shell input stream failure", e);
      }
      finally
      {
         if (tempCompleter != null)
         {
            reader.removeCompleter(tempCompleter);
         }
         reader.addCompleter(this.completer);
         reader.setHistoryEnabled(true);
         reader.setPrompt("");
      }
   }

   @Override
   public String promptRegex(final String message, final String regex)
   {
      String input;
      do
      {
         input = prompt(message);
      }
      while (!input.matches(regex));
      return input;
   }

   @Override
   public String promptRegex(final String message, final String pattern, final String defaultIfEmpty)
   {
      if (!defaultIfEmpty.matches(pattern))
      {
         throw new IllegalArgumentException("Default value [" + defaultIfEmpty + "] does not match required pattern ["
                  + pattern + "]");
      }

      String input;
      do
      {
         input = prompt(message + " [" + defaultIfEmpty + "]");
         if ("".equals(input.trim()))
         {
            input = defaultIfEmpty;
         }
      }
      while (!input.matches(pattern));
      return input;
   }

   @Override
   @SuppressWarnings("unchecked")
   public <T> T prompt(final String message, final Class<T> clazz)
   {
      Object result;
      Object input;
      do
      {
         input = prompt(message);
         try
         {
            result = DataConversion.convert(input, clazz);
         }
         catch (Exception e)
         {
            result = InvalidInput.INSTANCE;
         }
      }
      while ((result instanceof InvalidInput));

      return (T) result;
   }

   @Override
   @SuppressWarnings("unchecked")
   public <T> T prompt(final String message, final Class<T> clazz, final T defaultIfEmpty)
   {
      Object result;
      String input;
      do
      {
         input = prompt(message);
         if ((input == null) || "".equals(input.trim()))
         {
            result = defaultIfEmpty;
         }
         else
         {
            input = input.trim();
            try
            {
               result = DataConversion.convert(input, clazz);
            }
            catch (Exception e)
            {
               result = InvalidInput.INSTANCE;
            }
         }
      }
      while ((result instanceof InvalidInput));

      return (T) result;
   }

   @Override
   public boolean promptBoolean(final String message)
   {
      return promptBoolean(message, true);
   }

   @Override
   public boolean promptBoolean(final String message, final boolean defaultIfEmpty)
   {
      String query = " [Y/n] ";
      if (!defaultIfEmpty)
      {
         query = " [y/N] ";
      }

      return prompt(message + query, Boolean.class, defaultIfEmpty);
   }

   @Override
   public int promptChoice(final String message, final Object... options)
   {
      return promptChoice(message, Arrays.asList(options));
   }

   @Override
   public int promptChoice(final String message, final List<?> options)
   {
      if ((options == null) || options.isEmpty())
      {
         throw new IllegalArgumentException(
                  "promptChoice() Cannot ask user to select from a list of nothing. Ensure you have values in your options list.");
      }

      int count = 1;
      println(message);

      Object result = InvalidInput.INSTANCE;

      while (result instanceof InvalidInput)
      {
         println();
         for (Object entry : options)
         {
            println("  " + count + " - [" + entry + "]");
            count++;
         }
         println();
         int input = prompt("Choose an option by typing the number of the selection: ", Integer.class) - 1;
         if (input < options.size())
         {
            return input;
         }
         else
         {
            println("Invalid selection, please try again.");
         }
      }
      return -1;
   }

   @Override
   public <T> T promptChoiceTyped(final String message, final T... options)
   {
      return promptChoiceTyped(message, Arrays.asList(options));
   }

   @Override
   @SuppressWarnings("unchecked")
   public <T> T promptChoiceTyped(final String message, final List<T> options)
   {
      if ((options == null) || options.isEmpty())
      {
         throw new IllegalArgumentException(
                  "promptChoice() Cannot ask user to select from a list of nothing. Ensure you have values in your options list.");
      }
      if (options.size() == 1)
      {
         return options.get(0);
      }

      int count = 1;
      println(message);

      Object result = InvalidInput.INSTANCE;

      while (result instanceof InvalidInput)
      {
         println();
         for (T entry : options)
         {
            println("  " + count + " - [" + entry + "]");
            count++;
         }
         println();
         int input = prompt("Choose an option by typing the number of the selection: ", Integer.class) - 1;
         if ((input >= 0) && (input < options.size()))
         {
            result = options.get(input);
         }
         else
         {
            println("Invalid selection, please try again.");
         }
      }
      return (T) result;
   }

   @Override
   public int getHeight()
   {
      return reader.getTerminal().getHeight();
   }

   @Override
   public int getWidth()
   {
      return reader.getTerminal().getWidth();
   }

   public String escapeCode(final int code, final String value)
   {
      return new Ansi().a(value).fg(Ansi.Color.BLUE).toString();
   }

   @Override
   @SuppressWarnings("unchecked")
   public <T> T promptChoice(final String message, final Map<String, T> options)
   {
      int count = 1;
      println(message);
      List<Entry<String, T>> entries = new ArrayList<Map.Entry<String, T>>();
      entries.addAll(options.entrySet());

      Object result = InvalidInput.INSTANCE;
      while (result instanceof InvalidInput)
      {
         println();
         for (Entry<String, T> entry : entries)
         {
            println("  " + count + " - [" + entry.getKey() + "]");
            count++;
         }
         println();
         String input = prompt("Choose an option by typing the name or number of the selection: ");
         if (options.containsKey(input))
         {
            result = options.get(input);
         }
      }
      return (T) result;
   }

   @Override
   public String promptCommon(final String message, final PromptType type)
   {
      String result = promptRegex(message, type.getPattern());
      result = promptTypeConverter.convert(type, result);
      return result;
   }

   @Override
   public String promptCommon(final String message, final PromptType type, final String defaultIfEmpty)
   {
      String result = promptRegex(message, type.getPattern(), defaultIfEmpty);
      result = promptTypeConverter.convert(type, result);
      return result;
   }

   @Override
   public FileResource<?> promptFile(final String message)
   {
      String path = "";
      while ((path == null) || path.trim().isEmpty())
      {
         path = promptWithCompleter(message, new FileNameCompleter());
      }

      path = Files.canonicalize(path);
      Resource<File> resource = resourceFactory.getResourceFrom(new File(path));

      if (resource instanceof FileResource)
      {
         return (FileResource<?>) resource;
      }
      return null;
   }

   @Override
   public FileResource<?> promptFile(final String message, final FileResource<?> defaultIfEmpty)
   {
      FileResource<?> result = defaultIfEmpty;
      String path = promptWithCompleter(message, new FileNameCompleter());
      if (!"".equals(path) && (path != null) && !path.trim().isEmpty())
      {
         path = Files.canonicalize(path);
         Resource<File> resource = resourceFactory.getResourceFrom(new File(path));

         if (resource instanceof FileResource)
         {
            result = (FileResource<?>) resource;
         }
         else
         {
            result = null;
         }
      }
      return result;
   }
}