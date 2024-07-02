package cn.edu.sjtu.ops.grpcdemo;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.google.common.base.Joiner;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.shell.Command;
import org.apache.hadoop.fs.shell.CommandFactory;
import org.apache.hadoop.fs.shell.FsCommand;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.log4j.Logger;

public class Hdfs implements Closeable {
    private static final Logger log = Logger.getLogger(Hdfs.class);

    private volatile static Configuration conf;
    private FileSystem fs;
    private Trash trash;

    public static Hdfs getInstance() throws IOException {
        return new Hdfs();
    }

    Hdfs() throws IOException {
        synchronized(Hdfs.class) {
            init();
        }
        fs = FileSystem.get(conf);
        trash = new Trash(fs, conf);
    }

    void init() throws IOException {
        if (conf != null) {
            log.info("others already init hdfs!");
            return;
        }

        conf = new Configuration();

        conf.set("hadoop.security.authentication", "Kerberos");
        conf.set("hadoop.security.authorization", "true");
        conf.setBoolean("fs.hdfs.impl.disable.cache", true);
        conf.set("fs.hdfs.impl", DistributedFileSystem.class.getName());

        System.setProperty("java.security.krb5.conf", "conf/krb5.conf");
        UserGroupInformation.setConfiguration(conf);
        UserGroupInformation.loginUserFromKeytab("zhangxiping/127.0.0.1@EXAMPLE.COM", "D:\\Users\\temp\\zhangxiping.keytab");

        log.info("success init hdfs!");
        log.info("current user: "+UserGroupInformation.getCurrentUser());
        log.info("login user : "+UserGroupInformation.getLoginUser());
    }

    public String getAddress() {
        return conf.get("fs.defaultFS");
    }

    public String stat(String path, String time_format) throws IOException {
        String[] args = {time_format.equals("") ? "%y" : time_format, path};
        CommandResponse rv = runCommand("stat", args);
        if (rv.exit_code != 0) {
            throw new IOException(rv.out);
        }
        return rv.out;
    }

    public FileStatus[] ls(String path) throws IOException {
        return fs.listStatus(new Path(path));
    }

    public boolean isfile(String path) throws IOException {
        return fs.isFile(new Path(path));
    }

    public boolean exists(String path) throws IOException {
        return fs.exists(new Path(path));
    }

    public long getContentSize(String path) throws IOException {
        return fs.getContentSummary(new Path(path)).getLength();
    }

    public boolean delete(String path) throws IOException {
        return this.trash.moveToTrash(new Path(path));
    }

    public FSDataOutputStream create(String path, boolean overwrite) throws IOException {
        return fs.create(new Path(path), overwrite);
    }

    public FSDataInputStream open(String path) throws IOException {
        return fs.open(new Path(path));
    }

    public void rm(String path) throws IOException {
        this.trash.moveToTrash(new Path(path));
    }

    public void cp(String src, String dst, boolean overwrite) throws IOException {
        String[] arr = dst.split("/");
        String[] parent_list = Arrays.copyOfRange(arr, 0, arr.length - 1);
        Joiner joiner = Joiner.on("/");
        String parent = joiner.join(parent_list);
        Path parent_path = new Path(parent);
        Path dst_path = new Path(dst);
        Path src_path = new Path(src);
        if (!fs.exists(parent_path))
            fs.mkdirs(parent_path);

        if (fs.exists(dst_path) && !overwrite)
            throw new IOException(String.format("file or directory %s exists", dst));
        if (fs.exists(dst_path) && overwrite)
            fs.delete(dst_path, true);
        FileUtil.copy(fs, src_path, fs, dst_path, false, conf);
    }

    public boolean rename(String from, String to, boolean overwrite) throws IOException {
        Path path = new Path(to);
        Path toParentPath = path.getParent();
        if (!fs.exists(toParentPath)) {
            fs.mkdirs(toParentPath);
        }

        Path fromPath = new Path(from);
        Path toPath = new Path(to);
        if (fs.exists(toPath)) {
            if (!overwrite) {
                throw new IOException(String.format("file or directory %s already exists", toPath));
            } else {
                fs.delete(toPath, true);
            }
        }
        return fs.rename(fromPath, toPath);
    }

    public void mkdir(String path) throws IOException {
        Path _path = new Path(path);
        if (!fs.exists(_path))
            fs.mkdirs(_path);
    }

    public void mkdir(String path, String permission, List<String> acl) throws IOException {
        Path _path = new Path(path);
        // fs.mkdirs with 777 permission does not work
        if (!fs.exists(_path))
            fs.mkdirs(_path, FsPermission.valueOf(permission));
        fs.setPermission(_path, FsPermission.valueOf(permission));
        fs.setAcl(_path, AclEntry.parseAclSpec(String.join(",", acl), true));
    }

    public void addACL(String path, List<String> acl) throws IOException {
        Path _path = new Path(path);
        if (fs.exists(_path)) {
            List<AclEntry> aclEntries = fs.getAclStatus(_path).getEntries();
            aclEntries.addAll(AclEntry.parseAclSpec(String.join(",", acl), true));
            fs.setAcl(_path, aclEntries);
        }
    }

    public void removeACL(String path, List<String> acl) throws IOException {
        Path _path = new Path(path);
        if (fs.exists(_path)) {
            List<AclEntry> aclEntries = fs.getAclStatus(_path).getEntries();
            aclEntries.addAll(AclEntry.parseAclSpec(String.join(",", acl), false));
            fs.removeAclEntries(_path, aclEntries);
        }
    }

    public byte[] head(String path, int num_of_lines) throws IOException {
        List<Byte> ls = new ArrayList<>();
        int lines = 0;
        try (InputStream input = fs.open(new Path(path))) {
            int k;
            int cnt = 0;
            byte[] arr = new byte[1024];
            while (cnt < 1024 && (k = input.read(arr)) != -1 && lines < num_of_lines) {
                cnt++;
                int j = 0;
                while (j < k && lines < num_of_lines) {
                    if (arr[j] == '\n') {
                        lines++;
                    }
                    ls.add(arr[j]);
                    j++;
                }
            }
            byte[] res = new byte[ls.size()];
            for (int i = 0; i < ls.size(); i++)
                res[i] = ls.get(i);
            return res;
        }
    }

    public void download(String src, String dst, boolean overwrite) throws IOException {
        String[] arr = dst.split("/");
        String[] parent_list = Arrays.copyOfRange(arr, 0, arr.length - 1);
        Joiner joiner = Joiner.on("/");
        String parent = joiner.join(parent_list);
        File parent_path = new File(parent);
        if (!parent_path.exists())
            parent_path.mkdirs();
        File f = new File(dst);
        if (f.exists() && !overwrite)
            throw new IOException(String.format("file or directory %s exists", dst));
        if (f.exists() && overwrite)
            FileUtils.forceDelete(f);
        Path src_path = new Path(src);
        Path dst_path = new Path(dst);
        fs.setVerifyChecksum(false);
        fs.setWriteChecksum(false);
        fs.copyToLocalFile(false, src_path, dst_path, true);
    }

    public void upload(String src, String dst, boolean overwrite) throws IOException {
        String[] arr = dst.split("/");
        String[] parent_list = Arrays.copyOfRange(arr, 0, arr.length - 1);
        Joiner joiner = Joiner.on("/");
        String parent = joiner.join(parent_list);
        Path parent_path = new Path(parent);
        if (!fs.exists(parent_path))
            fs.mkdirs(parent_path);
        Path dst_path = new Path(dst);
        Path src_path = new Path(src);
        if (fs.exists(dst_path) && !overwrite)
            throw new IOException(String.format("file or directory %s exists", dst));
        if (fs.exists(dst_path) && overwrite)
            fs.delete(dst_path, true);
        fs.copyFromLocalFile(src_path, dst_path);
    }

    private CommandResponse runCommand(String cmdName, String[] arguments) {
        cmdName = "-" + cmdName;
        final Command command = getCommandInstance(cmdName, conf);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PrintStream printStream = new PrintStream(out);
        command.err = printStream;
        command.out = printStream;
        int res = command.run(arguments);
        CommandResponse rv = new CommandResponse();
        rv.out = out.toString();
        rv.exit_code = res;
        printStream.close();
        return rv;
    }

    private Command getCommandInstance(String cmdName, Configuration conf) {
        final CommandFactory commandFactory = new CommandFactory(conf);
        FsCommand.registerCommands(commandFactory);
        return commandFactory.getInstance(cmdName, conf);
    }

    private static class CommandResponse {
        int exit_code;
        String out;
    }

    @Override
    public void close() throws IOException {
        // no need to close fs now.
         fs.close();
    }

    public static void main(String[] args) throws IOException {
        Hdfs hdfs=  Hdfs.getInstance();
        hdfs.mkdir("/tmp/test");
        System.out.println(hdfs.ls("/tmp/test"));
        hdfs.exists("/tmp/test");
    }

}
