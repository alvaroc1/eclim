/**
 * Copyright (C) 2005 - 2011  Eric Van Dewoestine
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.eclim;

import java.io.FileInputStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import org.apache.tools.ant.taskdefs.condition.Os;

import org.eclim.util.CommandExecutor;
import org.eclim.util.IOUtils;

import static org.junit.Assert.assertNotNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Utility class to executing eclim commands and returning the results.
 *
 * @author Eric Van Dewoestine
 */
public class Eclim
{
  public static final String TEST_PROJECT = "eclim_unit_test";

  private static final String ECLIM =
    System.getProperty("eclipse.home") + "/eclim";
  private static final String PORT = System.getProperty("eclimd.port");
  private static final String PRETTY = "-pretty";
  private static final String COMMAND = "-command";

  private static final JsonParser JSON_PARSER = new JsonParser();

  private static String workspace;

  /**
   * Executes eclim using the supplied arguments.
   * <p/>
   * The "-command" argument will be prepended to the argument array you supply.
   *
   * @param args The arguments to pass to eclim.
   * @return The result of the command execution as a string.
   */
  public static Object execute(String[] args)
  {
    return execute(args, -1);
  }

  /**
   * Executes eclim using the supplied arguments.
   * <p/>
   * The "-command" argument will be prepended to the argument array you supply.
   *
   * @param args The arguments to pass to eclim.
   * @param timeout Timeout in milliseconds.
   * @return The result of the command execution as a string.
   */
  public static Object execute(String[] args, long timeout)
  {
    assertNotNull("Please configure property eclimd.port", PORT);
    assertNotNull("Please configure property eclipse.home", ECLIM);

    String[] arguments = null;
    if (Os.isFamily(Os.FAMILY_WINDOWS)){
      String eclimCmd = ECLIM + ".cmd";
      String drive = eclimCmd.substring(0, 2);
      arguments = new String[3];
      arguments[0] = "cmd.exe";
      arguments[1] = "/c";
      arguments[2] = drive + " && \"" + eclimCmd + "\" --nailgun-port " +
        PORT + " " + PRETTY + " " + COMMAND + " \"" + StringUtils.join(args, "\" \"") + "\"";
    }else{
      arguments = new String[args.length + 5];
      System.arraycopy(args, 0, arguments, 5, args.length);
      arguments[0] = ECLIM;
      arguments[1] = "--nailgun-port";
      arguments[2] = PORT;
      arguments[3] = PRETTY;
      arguments[4] = COMMAND;
    }

    System.out.println("Command: " + StringUtils.join(arguments, ' '));

    CommandExecutor process = null;
    try{
      process = CommandExecutor.execute(arguments, timeout);
    }catch(Exception e){
      throw new RuntimeException(e);
    }

    if(process.getReturnCode() == -1){
      process.destroy();
      throw new RuntimeException("Command timed out.");
    }

    if(process.getReturnCode() != 0){
      System.out.println("OUT: " + process.getResult());
      System.out.println("ERR: " + process.getErrorMessage());
      throw new RuntimeException("Command failed: " + process.getReturnCode());
    }

    String result = process.getResult();
    System.out.println("Result: " + result);

    if (result.trim().equals(StringUtils.EMPTY)){
      result = "\"\"";
    }
    return toType(JSON_PARSER.parse(result));
  }

  public static Object toType(JsonElement json)
  {
    // null
    if(json.isJsonNull()){
      return null;

    // int, double, boolean, String
    }else if(json.isJsonPrimitive()){
      JsonPrimitive prim = json.getAsJsonPrimitive();
      if(prim.isBoolean()){
        return prim.getAsBoolean();
      }else if(prim.isString()){
        return prim.getAsString();
      }else if(prim.isNumber()){
        if(prim.getAsString().indexOf('.') != -1){
          return prim.getAsDouble();
        }
        return prim.getAsInt();
      }

    // List
    }else if(json.isJsonArray()){
      ArrayList<Object> type = new ArrayList<Object>();
      for(JsonElement element : json.getAsJsonArray()){
        type.add(toType(element));
      }
      return type;

    // Map
    }else if(json.isJsonObject()){
      HashMap<String,Object> type = new HashMap<String,Object>();
      for(Map.Entry<String,JsonElement> entry : json.getAsJsonObject().entrySet()){
        type.put(entry.getKey(), toType(entry.getValue()));
      }
      return type;
    }

    return null;
  }

  /**
   * Gets the path to the current workspace.
   *
   * @return The workspace path.
   */
  public static String getWorkspace()
  {
    if(workspace == null){
      workspace = ((String)execute(new String[]{"workspace_dir"})).replace('\\', '/');
    }
    return workspace;
  }

  /**
   * Gets the path to the specified project.
   *
   * @param name The project name.
   * @return The project path.
   */
  @SuppressWarnings("unchecked")
  public static String getProjectPath(String name)
  {
    Object result = execute(new String[]{"project_info", "-p", name});
    if (result instanceof Map) {
      Map<String,String> info = (Map<String,String>)result;
      if (info.containsKey("path")){
        return info.get("path");
      }
    }
    return null;
  }

  /**
   * Determines if a project with the supplied name exists.
   *
   * @return true if the project exists, false otherwise.
   */
  public static boolean projectExists(String name)
  {
    return getProjectPath(name) != null;
  }

  /**
   * Constructs a full path for the given project relative file.
   *
   * @param file The project relative file path.
   * @return The absolute path to the file.
   */
  public static String resolveFile(String file)
  {
    return resolveFile(TEST_PROJECT, file);
  }

  /**
   * Constructs a full path for the given project relative file.
   *
   * @param project The name of the project the file belongs to.
   * @param file The project relative file path.
   * @return The absolute path to the file.
   */
  public static String resolveFile(String project, String file)
  {
    return new StringBuffer()
      .append(getProjectPath(project)).append('/')
      .append(file)
      .toString();
  }

  /**
   * Reads the project relative file into a string which is then returned.
   *
   * @param project The name of the project the file belongs to.
   * @param file The project relative file path.
   * @return The file contents as a string.
   */
  public static String fileToString(String project, String file)
  {
    String path = resolveFile(project, file);
    FileInputStream fin = null;
    try{
      fin = new FileInputStream(path);
      return IOUtils.toString(fin);
    }catch(Exception e){
      throw new RuntimeException(e);
    }finally{
      IOUtils.closeQuietly(fin);
    }
  }
}
