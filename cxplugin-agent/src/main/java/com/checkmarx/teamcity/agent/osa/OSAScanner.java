package com.checkmarx.teamcity.agent.osa;

import com.checkmarx.teamcity.agent.CxBuildLoggerAdapter;
import com.checkmarx.teamcity.common.client.dto.OSAFile;
import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tools.ant.types.selectors.SelectorUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class OSAScanner implements Serializable {
    private static final long serialVersionUID = 1L;
    private CxBuildLoggerAdapter logger;


    private static final String[] SUPPORTED_EXTENSIONS =
            {"jar", "war", "ear", "aar", "dll", "exe", "msi", "nupkg", "egg", "whl", "tar.gz", "gem", "deb", "udeb",
            "dmg", "drpm", "rpm", "pkg.tar.xz", "swf", "swc", "air", "apk", "zip", "gzip", "tar.bz2", "tgz", "c", "cc", "cp", "cpp", "css", "c++", "h", "hh", "hpp",
            "hxx", "h++", "m", "mm", "pch", "c#", "cs", "csharp", "go", "goc", "js", "plx", "pm", "ph", "cgi", "fcgi", "psgi", "al", "perl", "t", "p6m", "p6l", "nqp,6pl", "6pm",
            "p6", "php", "py", "rb", "swift", "clj", "cljx", "cljs", "cljc"};

    private static final String[] EXTRACTABLE_EXTENSIONS = {"jar", "war", "ear", "sca", "gem", "whl", "egg", "tar", "tar.gz", "tgz", "zip", "rar"};
    private static final String[] ALL_EXTENSIONS = ArrayUtils.addAll(SUPPORTED_EXTENSIONS, EXTRACTABLE_EXTENSIONS);


    private List<String> archiveInclude = new ArrayList<String>();
    private List<String> inclusions = new ArrayList<String>();
    private List<String> exclusions = new ArrayList<String>();

    /**
     *
     * @param filterPatterns - comma separated values of wildcard patterns - determines which files to calculate sha1
     *                          pattern prefixed with ! - means exclusion
     * @param archiveInclude - comma separated values of wildcard patterns - determines which files to extract
     */
    public OSAScanner(String filterPatterns, String archiveInclude, CxBuildLoggerAdapter logger) {
        if(StringUtils.isNotEmpty(archiveInclude)) {
            this.archiveInclude = Arrays.asList(archiveInclude.split("\\s*,\\s*"));
        }
        if(StringUtils.isNotEmpty(filterPatterns)) {
            setFilterPatterns(filterPatterns);
        }
        this.logger = logger;
    }

    /**
     *
     * scan for OSA compatible files and returns a list of sha1 + filename
     * the scan also recursively extracts archive files(extractableExtensions) and scan its contents
     *
     * @param baseDir - directory to scan from. should exist
     * @param baseTempDir - parent temp directory to extract the archive files
     * @param depth - the archive extraction recursion depth
     * @return - list of sha1 + filename from the baseDir and within archives
     * @throws CxOSAException - expected errors: create temp dir, list files
     * error handling for fail: extract archive / calculate sha1 / delete temp dir -  warning is logged
     */
    public List<OSAFile> scanFiles(File baseDir, File baseTempDir, int depth) throws CxOSAException {
        File extractTempDir = null;
        try {
            extractTempDir = createExtractTempDir(baseTempDir);
            return scanFilesRecursive(baseDir, extractTempDir, "", depth);
        } catch (Exception e) {
            throw new CxOSAException("Failed to scan directory for OSA files: " + e.getMessage(), e);

        } finally {
            try{
                if(extractTempDir != null) {
                    FileUtils.deleteDirectory(extractTempDir);
                }
            }catch (Exception e) {
                logger.warn("Failed to delete temp directory: ["+extractTempDir.getAbsolutePath()+"]");
            }
        }
    }


    /**
     * recursive function used by the wrapper scanFiles()
     *
     * @param virtualPath - base path used for reference to the parent directory (first time is empty)
     * @param tempDir - temporary directory to extract files to
     */
    private List<OSAFile> scanFilesRecursive(File baseDir, File tempDir, String virtualPath, int depth) {
        List<OSAFile> ret = new ArrayList<OSAFile>();

        if (depth < 0) {
            return ret;
        }

        List<File> files = getFiles(baseDir);

        for(File file : files) {
            String virtualFullPath = virtualPath + getRelativePath(baseDir, file);
            if(isCandidateForSha1(virtualFullPath)) {
                addSha1(file, ret);
            }

            if(isCandidateForExtract(virtualFullPath)) {
                //this directory should be created by the extractToTempDir() if there is any files to extract
                File nestedTempDir = new File (tempDir.getAbsolutePath() + "/" + file.getName() + "_extracted");

                boolean extracted = extractToTempDir(nestedTempDir, file, virtualFullPath);
                if(!extracted) {
                    continue;
                }

                List<OSAFile> tmp = scanFilesRecursive(nestedTempDir, nestedTempDir, virtualFullPath, depth - 1);
                ret.addAll(tmp);
            }
        }

        return ret;
    }

    //list file compatible to OSA, and the files that are extractable
    private List<File> getFiles(File baseDir) {
        return new ArrayList<File>(FileUtils.listFiles(baseDir, ALL_EXTENSIONS, true));
    }

    //extract the OSA compatible files and archives to temporary directory. also filters by includes/excludes
    private boolean extractToTempDir(File nestedTempDir, File zip, String virtualPath)  {

        try {
            ZipFile zipFile = new ZipFile(zip);
            List fileHeaders = zipFile.getFileHeaders();
            List<FileHeader> filtered = new ArrayList<FileHeader>();

            //first, filter the relevant files
            for (Object fileHeader1 : fileHeaders) {
                FileHeader fileHeader = (FileHeader) fileHeader1;
                String fileName = fileHeader.getFileName();
                if (!fileHeader.isDirectory() && (isCandidateForSha1(virtualPath + "/" + fileName) || isCandidateForExtract(virtualPath + "/" + fileName))) {
                    filtered.add(fileHeader);
                }
            }

            //now, extract the relevant files (if any):
            if(filtered.size() < 1 ) {
                return false;
            }

            //create the temp dir to extract to
            nestedTempDir.mkdirs();
            if(!nestedTempDir.exists()) {
                logger.warn("Failed to extract archive: ["+zip.getAbsolutePath()+"]: failed to create temp dir: ["+nestedTempDir.getAbsolutePath()+"]");
                return false;
            }

            //extract
            for (FileHeader fileHeader : filtered) {

                try {
                    zipFile.extractFile(fileHeader, nestedTempDir.getAbsolutePath());
                } catch (ZipException e) {
                    logger.warn("Failed to extract archive: ["+zip.getAbsolutePath()+"]: " + e.getMessage());
                }
            }

        } catch (ZipException e) {
            logger.warn("Failed to extract archive: ["+zip.getAbsolutePath()+"]: " + e.getMessage());
            return false;
        }

        return nestedTempDir.exists();
    }

    private boolean isCandidateForSha1(String relativePath) {
        return isCandidate(relativePath, SUPPORTED_EXTENSIONS, inclusions, exclusions);
    }

    private boolean isCandidateForExtract(String relativePath) {
        return isCandidate(relativePath, EXTRACTABLE_EXTENSIONS, archiveInclude, null);
    }

    /**
     * flow:
     *
     * 0. no match for extension -> return false
     * 1. no include, no exclude -> don't filter. return true
     * 2. no include, yes exclude -> return isExcludeMatch(file) ? false : true
     * 3. yes include, no exclude -> return isIncludeMatch(file) ?  true : false
     *
     * 4. yes include, yes exclude ->
     *  if(isExcludeMatch(file)) {
     *    return false
     *  }
     *
     *  return isIncludeMatch(file))
     *
     * @param relativePath
     * @return
     */
    private boolean isCandidate(String relativePath, String[] extensions, List<String> includeFilter, List<String> excludeFilter) {
        relativePath = relativePath.replaceAll("\\\\", "/");
        boolean isMatch = true;

        if(!FilenameUtils.isExtension(relativePath, extensions)) {
            return false;
        }

        if(excludeFilter != null) {
            for (String exclusion : excludeFilter) {
                if(SelectorUtils.matchPath(exclusion, relativePath, false)) {
                    return false;
                }
            }
        }

        if(includeFilter.size() > 0) {
            for (String inclusion : includeFilter) {
                if(SelectorUtils.matchPath(inclusion, relativePath, false)) {
                    return true;
                }
            }
            isMatch = false;
        }

        return isMatch;
    }

    //calculate sha1 of file, and add the filename + sha1 to the output parameter ret
    private void addSha1(File file, List<OSAFile> ret) {
        BOMInputStream is = null;
        try {
            is = new BOMInputStream(new FileInputStream(file));
            String sha1 = DigestUtils.sha1Hex(is);
            ret.add(new OSAFile(file.getName(), sha1));
        } catch (IOException e) {
            logger.warn("Failed to calculate sha1 for file: [" + file.getAbsolutePath() +"]. exception message: " + e.getMessage());
        } finally {
            IOUtils.closeQuietly(is);

        }
    }

    private String getRelativePath(File baseDir, File file) {
        Path pathAbsolute = file.toPath();
        Path pathBase = baseDir.toPath();
        return "/" + pathBase.relativize(pathAbsolute).toString();
    }

    private File createExtractTempDir(File tempDir) throws CxOSAException {

        File extractTempDir = new File(tempDir.getAbsolutePath() + "/CxOSA_extract");

        extractTempDir.mkdirs();
        if(!extractTempDir.exists()) {
            throw new CxOSAException("Failed to create directory ["+extractTempDir.getAbsolutePath()+"]");
        }

        return extractTempDir;
    }

    //convert comma separated values to include/exclude lists.
    private void setFilterPatterns(String filterPatterns) {
        String[] filters = filterPatterns.split("\\s*,\\s*"); //split by comma and trim (spaces + newline)
        for (String filter : filters) {
            if(StringUtils.isNotEmpty(filter)) {
                if (!filter.startsWith("!") ) {
                    inclusions.add(filter);
                } else if(filter.length() > 1){
                    filter = filter.substring(1); // Trim the "!"
                    exclusions.add(filter);
                }
            }
        }
    }
}
