// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.acceptance.api.revision;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;
import static com.google.gerrit.extensions.common.testing.DiffInfoSubject.assertThat;
import static com.google.gerrit.extensions.common.testing.FileInfoSubject.assertThat;
import static com.google.gerrit.git.ObjectIds.abbreviateName;
import static com.google.gerrit.reviewdb.client.Patch.COMMIT_MSG;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.common.RawInputUtil;
import com.google.gerrit.extensions.api.changes.FileApi;
import com.google.gerrit.extensions.api.changes.RebaseInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput;
import com.google.gerrit.extensions.client.Comment;
import com.google.gerrit.extensions.client.DiffPreferencesInfo;
import com.google.gerrit.extensions.common.ChangeType;
import com.google.gerrit.extensions.common.DiffInfo;
import com.google.gerrit.extensions.common.FileInfo;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.testing.ConfigSuite;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

public class RevisionDiffIT extends AbstractDaemonTest {
  // @RunWith(Parameterized.class) can't be used as AbstractDaemonTest is annotated with another
  // runner. Using different configs is a workaround to achieve the same.
  private static final String TEST_PARAMETER_MARKER = "test_only_parameter";
  private static final String CURRENT = "current";
  private static final String FILE_NAME = "some_file.txt";
  private static final String FILE_NAME2 = "another_file.txt";
  private static final String FILE_CONTENT =
      IntStream.rangeClosed(1, 100)
          .mapToObj(number -> String.format("Line %d\n", number))
          .collect(joining());
  private static final String FILE_CONTENT2 = "1st line\n2nd line\n3rd line\n";

  private boolean intraline;
  private ObjectId commit1;
  private String changeId;
  private String initialPatchSetId;

  @ConfigSuite.Config
  public static Config intralineConfig() {
    Config config = new Config();
    config.setBoolean(TEST_PARAMETER_MARKER, null, "intraline", true);
    return config;
  }

  @Before
  public void setUp() throws Exception {
    // Reduce flakiness of tests. (If tests aren't fast enough, we would use a fall-back
    // computation, which might yield different results.)
    baseConfig.setString("cache", "diff", "timeout", "1 minute");
    baseConfig.setString("cache", "diff_intraline", "timeout", "1 minute");

    intraline = baseConfig.getBoolean(TEST_PARAMETER_MARKER, "intraline", false);

    ObjectId headCommit = testRepo.getRepository().resolve("HEAD");
    commit1 =
        addCommit(headCommit, ImmutableMap.of(FILE_NAME, FILE_CONTENT, FILE_NAME2, FILE_CONTENT2));

    Result result = createEmptyChange();
    changeId = result.getChangeId();
    initialPatchSetId = result.getPatchSetId().getId();
  }

  @Test
  public void diff() throws Exception {
    // The assertions assume that intraline is false.
    assume().that(intraline).isFalse();

    String fileName = "a_new_file.txt";
    String fileContent = "First line\nSecond line\n";
    PushOneCommit.Result result = createChange("Add a file", fileName, fileContent);
    assertDiffForNewFile(result, fileName, fileContent);
    assertDiffForNewFile(result, COMMIT_MSG, result.getCommit().getFullMessage());
  }

  @Test
  public void deletedFileIsIncludedInDiff() throws Exception {
    gApi.changes().id(changeId).edit().deleteFile(FILE_NAME);
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles = gApi.changes().id(changeId).current().files();
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, FILE_NAME);
  }

  @Test
  public void numberOfLinesInDiffOfDeletedFileWithoutNewlineAtEndIsCorrect() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    gApi.changes().id(changeId).edit().deleteFile(filePath);
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(3);
    assertThat(diffInfo).metaB().isNull();
  }

  @Test
  public void numberOfLinesInFileInfoOfDeletedFileWithoutNewlineAtEndIsCorrect() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    gApi.changes().id(changeId).edit().deleteFile(filePath);
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(filePath)).linesInserted().isNull();
    assertThat(changedFiles.get(filePath)).linesDeleted().isEqualTo(3);
  }

  @Test
  public void numberOfLinesInDiffOfDeletedFileWithNewlineAtEndIsCorrect() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    gApi.changes().id(changeId).edit().deleteFile(filePath);
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(4);
    assertThat(diffInfo).metaB().isNull();
  }

  @Test
  public void numberOfLinesInFileInfoOfDeletedFileWithNewlineAtEndIsCorrect() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    gApi.changes().id(changeId).edit().deleteFile(filePath);
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(filePath)).linesInserted().isNull();
    // Inherited from Git: An empty last line is ignored in the count.
    assertThat(changedFiles.get(filePath)).linesDeleted().isEqualTo(3);
  }

  @Test
  public void deletedFileWithoutNewlineAtEndResultsInOneDiffEntry() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    gApi.changes().id(changeId).edit().deleteFile(filePath);
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo)
        .content()
        .onlyElement()
        .linesOfA()
        .containsExactly("Line 1", "Line 2", "Line 3");
  }

  @Test
  public void deletedFileWithNewlineAtEndResultsInOneDiffEntry() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    gApi.changes().id(changeId).edit().deleteFile(filePath);
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo)
        .content()
        .onlyElement()
        .linesOfA()
        .containsExactly("Line 1", "Line 2", "Line 3", "");
  }

  @Test
  public void addedFileIsIncludedInDiff() throws Exception {
    String newFilePath = "a_new_file.txt";
    String newFileContent = "arbitrary content";
    gApi.changes().id(changeId).edit().modifyFile(newFilePath, RawInputUtil.create(newFileContent));
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles = gApi.changes().id(changeId).current().files();
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, newFilePath);
  }

  @Test
  public void numberOfLinesInDiffOfAddedFileWithoutNewlineAtEndIsCorrect() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine2\nLine 3";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(initialPatchSetId).get();
    assertThat(diffInfo).metaA().isNull();
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(3);
  }

  @Test
  public void numberOfLinesInFileInfoOfAddedFileWithoutNewlineAtEndIsCorrect() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine2\nLine 3";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(filePath)).linesInserted().isEqualTo(3);
    assertThat(changedFiles.get(filePath)).linesDeleted().isNull();
  }

  @Test
  public void numberOfLinesInDiffOfAddedFileWithNewlineAtEndIsCorrect() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine2\nLine 3\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(initialPatchSetId).get();
    assertThat(diffInfo).metaA().isNull();
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(4);
  }

  @Test
  public void numberOfLinesInFileInfoOfAddedFileWithNewlineAtEndIsCorrect() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine2\nLine 3\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    // Inherited from Git: An empty last line is ignored in the count.
    assertThat(changedFiles.get(filePath)).linesInserted().isEqualTo(3);
    assertThat(changedFiles.get(filePath)).linesDeleted().isNull();
  }

  @Test
  public void addedFileWithoutNewlineAtEndResultsInOneDiffEntry() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(initialPatchSetId).get();
    assertThat(diffInfo)
        .content()
        .onlyElement()
        .linesOfB()
        .containsExactly("Line 1", "Line 2", "Line 3");
  }

  @Test
  public void addedFileWithNewlineAtEndResultsInOneDiffEntry() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(initialPatchSetId).get();
    assertThat(diffInfo)
        .content()
        .onlyElement()
        .linesOfB()
        .containsExactly("Line 1", "Line 2", "Line 3", "");
  }

  @Test
  public void renamedFileIsIncludedInDiff() throws Exception {
    String newFilePath = "a_new_file.txt";
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME, newFilePath);
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles = gApi.changes().id(changeId).current().files();
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, newFilePath);
  }

  @Test
  public void copiedFileTreatedAsAddedFileInDiff() throws Exception {
    String copyFilePath = "copy_of_some_file.txt";
    gApi.changes().id(changeId).edit().modifyFile(copyFilePath, RawInputUtil.create(FILE_CONTENT));
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles = gApi.changes().id(changeId).current().files();
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, copyFilePath);
    // If this ever changes, please add tests which cover copied files.
    assertThat(changedFiles.get(copyFilePath)).status().isEqualTo('A');
    assertThat(changedFiles.get(copyFilePath)).linesInserted().isEqualTo(100);
    assertThat(changedFiles.get(copyFilePath)).linesDeleted().isNull();
  }

  @Test
  public void addedBinaryFileIsIncludedInDiff() throws Exception {
    String imageFileName = "an_image.png";
    byte[] imageBytes = createRgbImage(255, 0, 0);
    gApi.changes().id(changeId).edit().modifyFile(imageFileName, RawInputUtil.create(imageBytes));
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles = gApi.changes().id(changeId).current().files();
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, imageFileName);
  }

  @Test
  public void modifiedBinaryFileIsIncludedInDiff() throws Exception {
    String imageFileName = "an_image.png";
    byte[] imageBytes1 = createRgbImage(255, 100, 0);
    ObjectId commit2 = addCommit(commit1, imageFileName, imageBytes1);

    rebaseChangeOn(changeId, commit2);
    byte[] imageBytes2 = createRgbImage(0, 100, 255);
    gApi.changes().id(changeId).edit().modifyFile(imageFileName, RawInputUtil.create(imageBytes2));
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles = gApi.changes().id(changeId).current().files();
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, imageFileName);
  }

  @Test
  public void diffOnMergeCommitChange() throws Exception {
    PushOneCommit.Result r = createMergeCommitChange("refs/for/master");

    DiffInfo diff;

    // automerge
    diff = getDiffRequest(r.getChangeId(), r.getCommit().name(), "foo").get();
    assertThat(diff.metaA.lines).isEqualTo(6);
    assertThat(diff.metaB.lines).isEqualTo(1);

    diff = getDiffRequest(r.getChangeId(), r.getCommit().name(), "bar").get();
    assertThat(diff.metaA.lines).isEqualTo(6);
    assertThat(diff.metaB.lines).isEqualTo(1);

    // parent 1
    diff = getDiffRequest(r.getChangeId(), r.getCommit().name(), "bar").withParent(1).get();
    assertThat(diff.metaA.lines).isEqualTo(1);
    assertThat(diff.metaB.lines).isEqualTo(1);

    // parent 2
    diff = getDiffRequest(r.getChangeId(), r.getCommit().name(), "foo").withParent(2).get();
    assertThat(diff.metaA.lines).isEqualTo(1);
    assertThat(diff.metaB.lines).isEqualTo(1);
  }

  @Test
  public void diffOfUnmodifiedFileMarksAllLinesAsCommon() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    gApi.changes().id(changeId).edit().modifyCommitMessage("An unchanged patchset");
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo)
        .content()
        .onlyElement()
        .commonLines()
        .containsExactly("Line 1", "Line 2", "Line 3", "")
        .inOrder();
    assertThat(diffInfo).content().onlyElement().linesOfA().isNull();
    assertThat(diffInfo).content().onlyElement().linesOfB().isNull();
  }

  @Test
  public void diffOfUnmodifiedFileWithNewlineAtEndHasEmptyLineAtEnd() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    gApi.changes().id(changeId).edit().modifyCommitMessage("An unchanged patchset");
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().onlyElement().commonLines().lastElement().isEqualTo("");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(4);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(4);
  }

  @Test
  public void diffOfUnmodifiedFileWithoutNewlineAtEndEndsWithLastLineContent() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    gApi.changes().id(changeId).edit().modifyCommitMessage("An unchanged patchset");
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().onlyElement().commonLines().lastElement().isEqualTo("Line 3");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(3);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(3);
  }

  @Test
  public void diffOfModifiedFileWithNewlineAtEndHasEmptyLineAtEnd() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, filePath, content -> content.replace("Line 1\n", "Line one\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().lastElement().commonLines().lastElement().isEqualTo("");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(4);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(4);
  }

  @Test
  public void diffOfModifiedFileWithoutNewlineAtEndEndsWithLastLineContent() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, filePath, content -> content.replace("Line 1\n", "Line one\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().lastElement().commonLines().lastElement().isEqualTo("Line 3");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(3);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(3);
  }

  @Test
  public void diffOfModifiedLastLineWithNewlineAtEndHasEmptyLineAtEnd() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, filePath, content -> content.replace("Line 3\n", "Line three\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().lastElement().commonLines().lastElement().isEqualTo("");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(4);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(4);
  }

  @Test
  public void diffOfModifiedLastLineWithoutNewlineAtEndEndsWithLastLineContent() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, filePath, content -> content.replace("Line 3", "Line three"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().lastElement().linesOfA().containsExactly("Line 3");
    assertThat(diffInfo).content().lastElement().linesOfB().containsExactly("Line three");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(3);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(3);
  }

  @Test
  public void addedNewlineAtEndOfFileIsMarkedInDiffWhenWhitespaceIsConsidered() throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withWhitespace(DiffPreferencesInfo.Whitespace.IGNORE_NONE)
            .withBase(previousPatchSetId)
            .get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 101");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line 101", "");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(101);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(102);
  }

  @Test
  public void addedNewlineAtEndOfFileIsMarkedInDiffWhenWhitespaceIsIgnored() throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withWhitespace(DiffPreferencesInfo.Whitespace.IGNORE_ALL)
            .withBase(previousPatchSetId)
            .get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().isNull();
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(101);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(102);
  }

  @Test
  public void addedNewlineAtEndOfFileMeansOneModifiedLine() throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("\n"));

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void addedLastLineWithoutNewlineBeforeAndAfterwardsIsMarkedInDiff() throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("\nLine 102"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withWhitespace(DiffPreferencesInfo.Whitespace.IGNORE_NONE)
            .withBase(previousPatchSetId)
            .get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 101");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line 101", "Line 102");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(101);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(102);
  }

  @Test
  public void addedLastLineWithoutNewlineBeforeAndAfterwardsMeansTwoModifiedLines()
      throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("\nLine 102"));

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(2);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void addedLastLineWithoutNewlineBeforeButWithOneAfterwardsIsMarkedInDiff()
      throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("\nLine 102\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withWhitespace(DiffPreferencesInfo.Whitespace.IGNORE_NONE)
            .withBase(previousPatchSetId)
            .get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 101");
    assertThat(diffInfo)
        .content()
        .element(1)
        .linesOfB()
        .containsExactly("Line 101", "Line 102", "");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(101);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(103);
  }

  @Test
  public void addedLastLineWithoutNewlineBeforeButWithOneAfterwardsMeansTwoModifiedLines()
      throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("\nLine 102\n"));

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    // Inherited from Git: An empty last line is ignored in the count.
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(2);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void addedLastLineWithNewlineBeforeAndAfterwardsIsMarkedInDiff() throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().isNull();
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line 101");
    assertThat(diffInfo).content().element(2).commonLines().containsExactly("");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(101);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(102);
  }

  @Test
  public void addedLastLineWithNewlineBeforeAndAfterwardsMeansOneInsertedLine() throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101\n"));

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isNull();
  }

  @Test
  public void addedLastLineWithNewlineBeforeButWithoutOneAfterwardsIsMarkedInDiff()
      throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line 101");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(101);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(101);
  }

  @Test
  public void addedLastLineWithNewlineBeforeButWithoutOneAfterwardsMeansOneInsertedLine()
      throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101"));

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    // Inherited from Git: An empty last line is ignored in the count.
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isNull();
  }

  @Test
  public void hunkForModifiedLastLineIsCombinedWithHunkForAddedNewlineAtEnd() throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 101", "Line one oh one\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 101");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line one oh one", "");
  }

  @Test
  public void intralineEditsForModifiedLastLineArePreservedWhenNewlineIsAlsoAddedAtEnd()
      throws Exception {
    assume().that(intraline).isTrue();

    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 101", "Line one oh one\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo)
        .content()
        .element(1)
        .intralineEditsOfA()
        .containsExactly(ImmutableList.of(5, 3));
    assertThat(diffInfo)
        .content()
        .element(1)
        .intralineEditsOfB()
        .containsExactly(ImmutableList.of(5, 11));
  }

  @Test
  public void hunkForModifiedSecondToLastLineIsNotCombinedWithHunkForAddedNewlineAtEnd()
      throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.concat("Line 101"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(
        changeId,
        FILE_NAME,
        fileContent -> fileContent.replace("Line 100\n", "Line one hundred\n").concat("\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 100");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line one hundred");
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().isNull();
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("");
  }

  @Test
  public void deletedNewlineAtEndOfFileIsMarkedInDiffWhenWhitespaceIsConsidered() throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100\n", "Line 100"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withBase(initialPatchSetId)
            .withWhitespace(DiffPreferencesInfo.Whitespace.IGNORE_NONE)
            .get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 100", "");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line 100");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(101);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(100);
  }

  @Test
  public void deletedNewlineAtEndOfFileIsMarkedInDiffWhenWhitespaceIsIgnored() throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100\n", "Line 100"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withBase(initialPatchSetId)
            .withWhitespace(DiffPreferencesInfo.Whitespace.IGNORE_ALL)
            .get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("");
    assertThat(diffInfo).content().element(1).linesOfB().isNull();

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(101);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(100);
  }

  @Test
  public void deletedNewlineAtEndOfFileMeansOneModifiedLine() throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100\n", "Line 100"));

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void deletedLastLineWithoutNewlineBeforeAndAfterwardsIsMarkedInDiff() throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100\n", "Line 100"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.replace("\nLine 100", ""));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withBase(previousPatchSetId)
            .withWhitespace(DiffPreferencesInfo.Whitespace.IGNORE_NONE)
            .get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 99", "Line 100");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line 99");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(100);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(99);
  }

  @Test
  public void deletedLastLineWithoutNewlineBeforeAndAfterwardsMeansTwoModifiedLines()
      throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100\n", "Line 100"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.replace("\nLine 100", ""));

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(2);
  }

  @Test
  public void deletedLastLineWithoutNewlineBeforeButWithOneAfterwardsIsMarkedInDiff()
      throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100\n", "Line 100"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100", ""));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withBase(previousPatchSetId)
            .withWhitespace(DiffPreferencesInfo.Whitespace.IGNORE_NONE)
            .get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 100");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(100);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(100);
  }

  @Test
  public void deletedLastLineWithoutNewlineBeforeButWithOneAfterwardsMeansOneDeletedLine()
      throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100\n", "Line 100"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100", ""));

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    // Inherited from Git: An empty last line is ignored in the count.
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isNull();
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void deletedLastLineWithNewlineBeforeAndAfterwardsIsMarkedInDiff() throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100\n", ""));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withBase(initialPatchSetId)
            .withWhitespace(DiffPreferencesInfo.Whitespace.IGNORE_NONE)
            .get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 100");
    assertThat(diffInfo).content().element(1).linesOfB().isNull();
    assertThat(diffInfo).content().element(2).commonLines().containsExactly("");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(101);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(100);
  }

  @Test
  public void deletedLastLineWithNewlineBeforeAndAfterwardsMeansOneDeletedLine() throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100\n", ""));

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isNull();
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void deletedLastLineWithNewlineBeforeButWithoutOneAfterwardsIsMarkedInDiff()
      throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("\nLine 100\n", ""));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withBase(initialPatchSetId)
            .withWhitespace(DiffPreferencesInfo.Whitespace.IGNORE_NONE)
            .get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 99", "Line 100", "");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line 99");

    assertThat(diffInfo).metaA().totalLineCount().isEqualTo(101);
    assertThat(diffInfo).metaB().totalLineCount().isEqualTo(99);
  }

  @Test
  public void deletedLastLineWithNewlineBeforeButWithoutOneAfterwardsMeansTwoModifiedLines()
      throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("\nLine 100\n", ""));

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(2);
  }

  @Test
  public void hunkForModifiedLastLineIsCombinedWithHunkForDeletedNewlineAtEnd() throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100\n", "Line one hundred"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 100", "");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line one hundred");
  }

  @Test
  public void intralineEditsForModifiedLastLineArePreservedWhenNewlineIsAlsoDeletedAtEnd()
      throws Exception {
    assume().that(intraline).isTrue();

    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 100\n", "Line one hundred"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo)
        .content()
        .element(1)
        .intralineEditsOfA()
        .containsExactly(ImmutableList.of(5, 4));
    assertThat(diffInfo)
        .content()
        .element(1)
        .intralineEditsOfB()
        .containsExactly(ImmutableList.of(5, 11));
  }

  @Test
  public void hunkForModifiedSecondToLastLineIsNotCombinedWithHunkForDeletedNewlineAtEnd()
      throws Exception {
    addModifiedPatchSet(
        changeId,
        FILE_NAME,
        fileContent ->
            fileContent
                .replace("Line 99\n", "Line ninety-nine\n")
                .replace("Line 100\n", "Line 100"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 99");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line ninety-nine");
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("");
    assertThat(diffInfo).content().element(3).linesOfB().isNull();
  }

  @Test
  public void addedUnrelatedFileIsIgnored_ForPatchSetDiffWithRebase() throws Exception {
    ObjectId commit2 = addCommit(commit1, "file_added_in_another_commit.txt", "Some file content");

    rebaseChangeOn(changeId, commit2);
    addModifiedPatchSet(changeId, FILE_NAME, "Another line\n"::concat);

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, FILE_NAME);
  }

  @Test
  public void removedUnrelatedFileIsIgnored_ForPatchSetDiffWithRebase() throws Exception {
    ObjectId commit2 = addCommitRemovingFiles(commit1, FILE_NAME2);

    rebaseChangeOn(changeId, commit2);
    addModifiedPatchSet(changeId, FILE_NAME, "Another line\n"::concat);

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, FILE_NAME);
  }

  @Test
  public void renamedUnrelatedFileIsIgnored_ForPatchSetDiffWithRebase() throws Exception {
    ObjectId commit2 = addCommitRenamingFile(commit1, FILE_NAME2, "a_new_file_name.txt");

    rebaseChangeOn(changeId, commit2);
    addModifiedPatchSet(changeId, FILE_NAME, "Another line\n"::concat);

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, FILE_NAME);
  }

  @Test
  public void renamedUnrelatedFileIsIgnored_ForPatchSetDiffWithRebase_WhenEquallyModifiedInBoth()
      throws Exception {
    Function<String, String> contentModification =
        fileContent -> fileContent.replace("1st line\n", "First line\n");
    addModifiedPatchSet(changeId, FILE_NAME2, contentModification);
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    // Revert the modification to be able to rebase.
    addModifiedPatchSet(
        changeId, FILE_NAME2, fileContent -> fileContent.replace("First line\n", "1st line\n"));

    String renamedFileName = "renamed_file.txt";
    ObjectId commit2 = addCommitRenamingFile(commit1, FILE_NAME2, renamedFileName);
    rebaseChangeOn(changeId, commit2);
    addModifiedPatchSet(changeId, renamedFileName, contentModification);
    addModifiedPatchSet(changeId, FILE_NAME, "Another line\n"::concat);

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, FILE_NAME);
  }

  @Test
  public void renamedUnrelatedFileIsIgnored_ForPatchSetDiffWithRebase_WhenModifiedDuringRebase()
      throws Exception {
    String renamedFilePath = "renamed_some_file.txt";
    ObjectId commit2 =
        addCommit(commit1, FILE_NAME, FILE_CONTENT.replace("Line 5\n", "Line five\n"));
    ObjectId commit3 = addCommitRenamingFile(commit2, FILE_NAME, renamedFilePath);

    rebaseChangeOn(changeId, commit3);

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG);
  }

  @Test
  public void fileRenamedDuringRebaseSameAsInPatchSetIsIgnored() throws Exception {
    String renamedFileName = "renamed_file.txt";
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME, renamedFileName);
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    // Revert the renaming to be able to rebase.
    gApi.changes().id(changeId).edit().renameFile(renamedFileName, FILE_NAME);
    gApi.changes().id(changeId).edit().publish();

    ObjectId commit2 = addCommitRenamingFile(commit1, FILE_NAME, renamedFileName);
    rebaseChangeOn(changeId, commit2);

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG);
  }

  @Test
  public void fileWithRebaseHunksRenamedDuringRebaseSameAsInPatchSetIsIgnored() throws Exception {
    String renamedFileName = "renamed_file.txt";
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME, renamedFileName);
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    // Revert the renaming to be able to rebase.
    gApi.changes().id(changeId).edit().renameFile(renamedFileName, FILE_NAME);
    gApi.changes().id(changeId).edit().publish();

    ObjectId commit2 =
        addCommit(commit1, FILE_NAME, FILE_CONTENT.replace("Line 10\n", "Line ten\n"));
    ObjectId commit3 = addCommitRenamingFile(commit2, FILE_NAME, renamedFileName);
    rebaseChangeOn(changeId, commit3);

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG);
  }

  @Test
  public void filesNotTouchedByPatchSetsAndContainingOnlyRebaseHunksAreIgnored() throws Exception {
    String newFileContent = FILE_CONTENT.replace("Line 10\n", "Line ten\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);
    ObjectId commit3 = addCommitRenamingFile(commit2, FILE_NAME2, "a_new_file_name.txt");

    rebaseChangeOn(changeId, commit3);

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG);
  }

  @Test
  public void filesTouchedByPatchSetsAndContainingOnlyRebaseHunksAreIgnored() throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 50\n", "Line fifty\n"));
    addModifiedPatchSet(
        changeId, FILE_NAME2, fileContent -> fileContent.replace("1st line\n", "First line\n"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    // Revert the modification to allow rebasing.
    addModifiedPatchSet(
        changeId, FILE_NAME2, fileContent -> fileContent.replace("First line\n", "1st line\n"));

    String newFileContent = FILE_CONTENT.replace("Line 10\n", "Line ten\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);
    String newFilePath = "a_new_file_name.txt";
    ObjectId commit3 = addCommitRenamingFile(commit2, FILE_NAME2, newFilePath);

    rebaseChangeOn(changeId, commit3);
    // Apply the modification again to bring the file into the same state as for the previous
    // patch set.
    addModifiedPatchSet(
        changeId, newFilePath, fileContent -> fileContent.replace("1st line\n", "First line\n"));

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG);
  }

  @Test
  public void singleHunkAtBeginningIsFollowedByCorrectCommonLines() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, filePath, content -> content.replace("Line 1\n", "Line one\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).linesOfA().isNotEmpty();
    assertThat(diffInfo).content().element(0).linesOfB().isNotEmpty();
    assertThat(diffInfo)
        .content()
        .element(1)
        .commonLines()
        .containsExactly("Line 2", "Line 3", "Line 4", "Line 5", "")
        .inOrder();
  }

  @Test
  public void singleHunkAtEndIsPrecededByCorrectCommonLines() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, filePath, content -> content.replace("Line 5\n", "Line five\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo)
        .content()
        .element(0)
        .commonLines()
        .containsExactly("Line 1", "Line 2", "Line 3", "Line 4")
        .inOrder();
    assertThat(diffInfo).content().element(1).linesOfA().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfB().isNotEmpty();
  }

  @Test
  public void singleHunkInTheMiddleIsSurroundedByCorrectCommonLines() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\nLine 6\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(changeId, filePath, content -> content.replace("Line 3\n", "Line three\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo)
        .content()
        .element(0)
        .commonLines()
        .containsExactly("Line 1", "Line 2")
        .inOrder();
    assertThat(diffInfo).content().element(1).linesOfA().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfB().isNotEmpty();
    assertThat(diffInfo)
        .content()
        .element(2)
        .commonLines()
        .containsExactly("Line 4", "Line 5", "Line 6", "")
        .inOrder();
  }

  @Test
  public void twoHunksAreSeparatedByCorrectCommonLines() throws Exception {
    String filePath = "a_new_file.txt";
    String fileContent = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n";
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(
        changeId,
        filePath,
        content -> content.replace("Line 2\n", "Line two\n").replace("Line 5\n", "Line five\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, filePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfB().isNotEmpty();
    assertThat(diffInfo)
        .content()
        .element(2)
        .commonLines()
        .containsExactly("Line 3", "Line 4")
        .inOrder();
    assertThat(diffInfo).content().element(3).linesOfA().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfB().isNotEmpty();
  }

  @Test
  public void rebaseHunksAtStartOfFileAreIdentified() throws Exception {
    String newFileContent =
        FILE_CONTENT.replace("Line 1\n", "Line one\n").replace("Line 5\n", "Line five\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification =
        fileContent -> fileContent.replace("Line 50\n", "Line fifty\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).linesOfA().containsExactly("Line 1");
    assertThat(diffInfo).content().element(0).linesOfB().containsExactly("Line one");
    assertThat(diffInfo).content().element(0).isDueToRebase();
    assertThat(diffInfo).content().element(1).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(2).linesOfA().containsExactly("Line 5");
    assertThat(diffInfo).content().element(2).linesOfB().containsExactly("Line five");
    assertThat(diffInfo).content().element(2).isDueToRebase();
    assertThat(diffInfo).content().element(3).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(4).linesOfA().containsExactly("Line 50");
    assertThat(diffInfo).content().element(4).linesOfB().containsExactly("Line fifty");
    assertThat(diffInfo).content().element(4).isNotDueToRebase();
    assertThat(diffInfo).content().element(5).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void rebaseHunksAtEndOfFileAreIdentified() throws Exception {
    String newFileContent =
        FILE_CONTENT
            .replace("Line 60\n", "Line sixty\n")
            .replace("Line 100\n", "Line one hundred\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification =
        fileContent -> fileContent.replace("Line 50\n", "Line fifty\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 50");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line fifty");
    assertThat(diffInfo).content().element(1).isNotDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("Line 60");
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("Line sixty");
    assertThat(diffInfo).content().element(3).isDueToRebase();
    assertThat(diffInfo).content().element(4).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(5).linesOfA().containsExactly("Line 100");
    assertThat(diffInfo).content().element(5).linesOfB().containsExactly("Line one hundred");
    assertThat(diffInfo).content().element(5).isDueToRebase();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void rebaseHunksInBetweenRegularHunksAreIdentified() throws Exception {
    String newFileContent =
        FILE_CONTENT.replace("Line 40\n", "Line forty\n").replace("Line 45\n", "Line forty five\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification =
        fileContent ->
            fileContent
                .replace("Line 1\n", "Line one\n")
                .replace("Line 100\n", "Line one hundred\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).linesOfA().containsExactly("Line 1");
    assertThat(diffInfo).content().element(0).linesOfB().containsExactly("Line one");
    assertThat(diffInfo).content().element(0).isNotDueToRebase();
    assertThat(diffInfo).content().element(1).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(2).linesOfA().containsExactly("Line 40");
    assertThat(diffInfo).content().element(2).linesOfB().containsExactly("Line forty");
    assertThat(diffInfo).content().element(2).isDueToRebase();
    assertThat(diffInfo).content().element(3).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(4).linesOfA().containsExactly("Line 45");
    assertThat(diffInfo).content().element(4).linesOfB().containsExactly("Line forty five");
    assertThat(diffInfo).content().element(4).isDueToRebase();
    assertThat(diffInfo).content().element(5).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(6).linesOfA().containsExactly("Line 100");
    assertThat(diffInfo).content().element(6).linesOfB().containsExactly("Line one hundred");
    assertThat(diffInfo).content().element(6).isNotDueToRebase();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(2);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(2);
  }

  @Test
  public void rebaseHunkIsIdentifiedWhenMovedDownInPreviousPatchSet() throws Exception {
    // Move the code down by introducing additional lines (pure insert + enlarging replacement) in
    // the previous patch set.
    Function<String, String> contentModification1 =
        fileContent ->
            "Line zero\n" + fileContent.replace("Line 10\n", "Line ten\nLine ten and a half\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification1);
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    String newFileContent = FILE_CONTENT.replace("Line 40\n", "Line forty\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification2 =
        fileContent -> fileContent.replace("Line 100\n", "Line one hundred\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification2);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 40");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line forty");
    assertThat(diffInfo).content().element(1).isDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("Line 100");
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("Line one hundred");
    assertThat(diffInfo).content().element(3).isNotDueToRebase();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void rebaseHunkIsIdentifiedWhenMovedDownInLatestPatchSet() throws Exception {
    String newFileContent = FILE_CONTENT.replace("Line 40\n", "Line forty\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    // Move the code down by introducing additional lines (pure insert + enlarging replacement) in
    // the latest patch set.
    Function<String, String> contentModification =
        fileContent ->
            "Line zero\n" + fileContent.replace("Line 10\n", "Line ten\nLine ten and a half\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).linesOfA().isNull();
    assertThat(diffInfo).content().element(0).linesOfB().containsExactly("Line zero");
    assertThat(diffInfo).content().element(0).isNotDueToRebase();
    assertThat(diffInfo).content().element(1).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(2).linesOfA().containsExactly("Line 10");
    assertThat(diffInfo)
        .content()
        .element(2)
        .linesOfB()
        .containsExactly("Line ten", "Line ten and a half");
    assertThat(diffInfo).content().element(2).isNotDueToRebase();
    assertThat(diffInfo).content().element(3).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(4).linesOfA().containsExactly("Line 40");
    assertThat(diffInfo).content().element(4).linesOfB().containsExactly("Line forty");
    assertThat(diffInfo).content().element(4).isDueToRebase();
    assertThat(diffInfo).content().element(5).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(3);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void rebaseHunkIsIdentifiedWhenMovedUpInPreviousPatchSet() throws Exception {
    // Move the code up by removing lines (pure deletion + shrinking replacement) in the previous
    // patch set.
    Function<String, String> contentModification1 =
        fileContent ->
            fileContent.replace("Line 1\n", "").replace("Line 10\nLine 11\n", "Line ten\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification1);
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    String newFileContent = FILE_CONTENT.replace("Line 40\n", "Line forty\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification2 =
        fileContent -> fileContent.replace("Line 100\n", "Line one hundred\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification2);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 40");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line forty");
    assertThat(diffInfo).content().element(1).isDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("Line 100");
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("Line one hundred");
    assertThat(diffInfo).content().element(3).isNotDueToRebase();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void rebaseHunkIsIdentifiedWhenMovedUpInLatestPatchSet() throws Exception {
    String newFileContent = FILE_CONTENT.replace("Line 40\n", "Line forty\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    // Move the code up by removing lines (pure deletion + shrinking replacement) in the latest
    // patch set.
    Function<String, String> contentModification =
        fileContent ->
            fileContent.replace("Line 1\n", "").replace("Line 10\nLine 11\n", "Line ten\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).linesOfA().containsExactly("Line 1");
    assertThat(diffInfo).content().element(0).linesOfB().isNull();
    assertThat(diffInfo).content().element(0).isNotDueToRebase();
    assertThat(diffInfo).content().element(1).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(2).linesOfA().containsExactly("Line 10", "Line 11");
    assertThat(diffInfo).content().element(2).linesOfB().containsExactly("Line ten");
    assertThat(diffInfo).content().element(2).isNotDueToRebase();
    assertThat(diffInfo).content().element(3).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(4).linesOfA().containsExactly("Line 40");
    assertThat(diffInfo).content().element(4).linesOfB().containsExactly("Line forty");
    assertThat(diffInfo).content().element(4).isDueToRebase();
    assertThat(diffInfo).content().element(5).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(3);
  }

  @Test
  public void modifiedRebaseHunkWithSameRegionConsideredAsRegularHunk() throws Exception {
    String newFileContent = FILE_CONTENT.replace("Line 40\n", "Line forty\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification =
        fileContent -> fileContent.replace("Line forty\n", "Line modified after rebase\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 40");
    assertThat(diffInfo)
        .content()
        .element(1)
        .linesOfB()
        .containsExactly("Line modified after rebase");
    assertThat(diffInfo).content().element(1).isNotDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void rebaseHunkOverlappingAtBeginningConsideredAsRegularHunk() throws Exception {
    String newFileContent =
        FILE_CONTENT.replace("Line 40\nLine 41\n", "Line forty\nLine forty one\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification =
        fileContent ->
            fileContent
                .replace("Line 39\n", "Line thirty nine\n")
                .replace("Line forty one\n", "Line 41\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 39", "Line 40");
    assertThat(diffInfo)
        .content()
        .element(1)
        .linesOfB()
        .containsExactly("Line thirty nine", "Line forty");
    assertThat(diffInfo).content().element(1).isNotDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(2);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(2);
  }

  @Test
  public void rebaseHunkOverlappingAtEndConsideredAsRegularHunk() throws Exception {
    String newFileContent =
        FILE_CONTENT.replace("Line 40\nLine 41\n", "Line forty\nLine forty one\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification =
        fileContent ->
            fileContent
                .replace("Line forty\n", "Line 40\n")
                .replace("Line 42\n", "Line forty two\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 41", "Line 42");
    assertThat(diffInfo)
        .content()
        .element(1)
        .linesOfB()
        .containsExactly("Line forty one", "Line forty two");
    assertThat(diffInfo).content().element(1).isNotDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(2);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(2);
  }

  @Test
  public void rebaseHunkModifiedInsideConsideredAsRegularHunk() throws Exception {
    String newFileContent =
        FILE_CONTENT.replace(
            "Line 39\nLine 40\nLine 41\n", "Line thirty nine\nLine forty\nLine forty one\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification =
        fileContent -> fileContent.replace("Line forty\n", "A different line forty\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo)
        .content()
        .element(1)
        .linesOfA()
        .containsExactly("Line 39", "Line 40", "Line 41");
    assertThat(diffInfo)
        .content()
        .element(1)
        .linesOfB()
        .containsExactly("Line thirty nine", "A different line forty", "Line forty one");
    assertThat(diffInfo).content().element(1).isNotDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(3);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(3);
  }

  @Test
  public void rebaseHunkAfterLineNumberChangingOverlappingHunksIsIdentified() throws Exception {
    String newFileContent =
        FILE_CONTENT
            .replace("Line 40\nLine 41\n", "Line forty\nLine forty one\n")
            .replace("Line 60\n", "Line sixty\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification =
        fileContent ->
            fileContent
                .replace("Line forty\n", "Line 40\n")
                .replace("Line 42\n", "Line forty two\nLine forty two and a half\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 41", "Line 42");
    assertThat(diffInfo)
        .content()
        .element(1)
        .linesOfB()
        .containsExactly("Line forty one", "Line forty two", "Line forty two and a half");
    assertThat(diffInfo).content().element(1).isNotDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("Line 60");
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("Line sixty");
    assertThat(diffInfo).content().element(3).isDueToRebase();
    assertThat(diffInfo).content().element(4).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(3);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(2);
  }

  @Test
  public void rebaseHunksOneLineApartFromRegularHunkAreIdentified() throws Exception {
    String newFileContent =
        FILE_CONTENT.replace("Line 1\n", "Line one\n").replace("Line 5\n", "Line five\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification =
        fileContent -> fileContent.replace("Line 3\n", "Line three\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).linesOfA().containsExactly("Line 1");
    assertThat(diffInfo).content().element(0).linesOfB().containsExactly("Line one");
    assertThat(diffInfo).content().element(0).isDueToRebase();
    assertThat(diffInfo).content().element(1).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(2).linesOfA().containsExactly("Line 3");
    assertThat(diffInfo).content().element(2).linesOfB().containsExactly("Line three");
    assertThat(diffInfo).content().element(2).isNotDueToRebase();
    assertThat(diffInfo).content().element(3).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(4).linesOfA().containsExactly("Line 5");
    assertThat(diffInfo).content().element(4).linesOfB().containsExactly("Line five");
    assertThat(diffInfo).content().element(4).isDueToRebase();
    assertThat(diffInfo).content().element(5).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void rebaseHunksDirectlyTouchingHunksOfPatchSetsNotModifiedBetweenThemAreIdentified()
      throws Exception {
    // Add to hunks in a patch set and remove them in a further patch set to allow rebasing.
    Function<String, String> contentModification =
        fileContent ->
            fileContent.replace("Line 1\n", "Line one\n").replace("Line 3\n", "Line three\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    Function<String, String> reverseContentModification =
        fileContent ->
            fileContent.replace("Line one\n", "Line 1\n").replace("Line three\n", "Line 3\n");
    addModifiedPatchSet(changeId, FILE_NAME, reverseContentModification);

    String newFileContent = FILE_CONTENT.replace("Line 2\n", "Line two\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);
    rebaseChangeOn(changeId, commit2);

    // Add the hunks again and modify another line so that we get a diff for the file.
    // (Files with only edits due to rebase are filtered out.)
    addModifiedPatchSet(
        changeId,
        FILE_NAME,
        contentModification.andThen(fileContent -> fileContent.replace("Line 10\n", "Line ten\n")));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 2");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line two");
    assertThat(diffInfo).content().element(1).isDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("Line 10");
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("Line ten");
    assertThat(diffInfo).content().element(3).isNotDueToRebase();
    assertThat(diffInfo).content().element(4).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void multipleRebaseEditsMixedWithRegularEditsCanBeIdentified() throws Exception {
    addModifiedPatchSet(
        changeId,
        FILE_NAME,
        fileContent -> fileContent.replace("Line 7\n", "Line seven\n").replace("Line 24\n", ""));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    ObjectId commit2 =
        addCommit(
            commit1,
            FILE_NAME,
            FILE_CONTENT
                .replace("Line 2\n", "Line two\n")
                .replace("Line 18\nLine 19\n", "Line eighteen\nLine nineteen\n")
                .replace("Line 50\n", "Line fifty\n"));

    rebaseChangeOn(changeId, commit2);
    addModifiedPatchSet(
        changeId,
        FILE_NAME,
        fileContent ->
            fileContent
                .replace("Line seven\n", "Line 7\n")
                .replace("Line 9\n", "Line nine\n")
                .replace("Line 60\n", "Line sixty\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 2");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line two");
    assertThat(diffInfo).content().element(1).isDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("Line seven");
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("Line 7");
    assertThat(diffInfo).content().element(3).isNotDueToRebase();
    assertThat(diffInfo).content().element(4).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(5).linesOfA().containsExactly("Line 9");
    assertThat(diffInfo).content().element(5).linesOfB().containsExactly("Line nine");
    assertThat(diffInfo).content().element(5).isNotDueToRebase();
    assertThat(diffInfo).content().element(6).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(7).linesOfA().containsExactly("Line 18", "Line 19");
    assertThat(diffInfo)
        .content()
        .element(7)
        .linesOfB()
        .containsExactly("Line eighteen", "Line nineteen");
    assertThat(diffInfo).content().element(7).isDueToRebase();
    assertThat(diffInfo).content().element(8).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(9).linesOfA().containsExactly("Line 50");
    assertThat(diffInfo).content().element(9).linesOfB().containsExactly("Line fifty");
    assertThat(diffInfo).content().element(9).isDueToRebase();
    assertThat(diffInfo).content().element(10).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(11).linesOfA().containsExactly("Line 60");
    assertThat(diffInfo).content().element(11).linesOfB().containsExactly("Line sixty");
    assertThat(diffInfo).content().element(11).isNotDueToRebase();
    assertThat(diffInfo).content().element(12).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(3);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(3);
  }

  @Test
  public void deletedFileDuringRebaseConsideredAsRegularHunkWhenModifiedInDiff() throws Exception {
    // Modify the file and revert the modifications to allow rebasing.
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line 50\n", "Line fifty\n"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(
        changeId, FILE_NAME, fileContent -> fileContent.replace("Line fifty\n", "Line 50\n"));

    ObjectId commit2 = addCommitRemovingFiles(commit1, FILE_NAME);

    rebaseChangeOn(changeId, commit2);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).changeType().isEqualTo(ChangeType.DELETED);
    assertThat(diffInfo).content().element(0).linesOfA().hasSize(101);
    assertThat(diffInfo).content().element(0).linesOfB().isNull();
    assertThat(diffInfo).content().element(0).isNotDueToRebase();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isNull();
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(100);
  }

  @Test
  public void addedFileDuringRebaseConsideredAsRegularHunkWhenModifiedInDiff() throws Exception {
    String newFilePath = "a_new_file.txt";
    ObjectId commit2 = addCommit(commit1, newFilePath, "1st line\n2nd line\n3rd line\n");

    rebaseChangeOn(changeId, commit2);
    addModifiedPatchSet(
        changeId, newFilePath, fileContent -> fileContent.replace("1st line\n", "First line\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, newFilePath).withBase(initialPatchSetId).get();
    assertThat(diffInfo).changeType().isEqualTo(ChangeType.ADDED);
    assertThat(diffInfo).content().element(0).linesOfA().isNull();
    assertThat(diffInfo).content().element(0).linesOfB().hasSize(4);
    assertThat(diffInfo).content().element(0).isNotDueToRebase();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(newFilePath)).linesInserted().isEqualTo(3);
    assertThat(changedFiles.get(newFilePath)).linesDeleted().isNull();
  }

  @Test
  public void rebaseHunkInRenamedFileIsIdentified_WhenFileIsRenamedDuringRebase() throws Exception {
    String renamedFilePath = "renamed_some_file.txt";
    ObjectId commit2 =
        addCommit(commit1, FILE_NAME, FILE_CONTENT.replace("Line 1\n", "Line one\n"));
    ObjectId commit3 = addCommitRenamingFile(commit2, FILE_NAME, renamedFilePath);

    rebaseChangeOn(changeId, commit3);
    Function<String, String> contentModification =
        fileContent -> fileContent.replace("Line 50\n", "Line fifty\n");
    addModifiedPatchSet(changeId, renamedFilePath, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, renamedFilePath).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).linesOfA().containsExactly("Line 1");
    assertThat(diffInfo).content().element(0).linesOfB().containsExactly("Line one");
    assertThat(diffInfo).content().element(0).isDueToRebase();
    assertThat(diffInfo).content().element(1).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(2).linesOfA().containsExactly("Line 50");
    assertThat(diffInfo).content().element(2).linesOfB().containsExactly("Line fifty");
    assertThat(diffInfo).content().element(2).isNotDueToRebase();
    assertThat(diffInfo).content().element(3).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.get(renamedFilePath)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(renamedFilePath)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void rebaseHunkInRenamedFileIsIdentified_WhenFileIsRenamedInPatchSets() throws Exception {
    String renamedFilePath = "renamed_some_file.txt";
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME, renamedFilePath);
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    // Revert the renaming to be able to rebase.
    gApi.changes().id(changeId).edit().renameFile(renamedFilePath, FILE_NAME);
    gApi.changes().id(changeId).edit().publish();

    ObjectId commit2 =
        addCommit(commit1, FILE_NAME, FILE_CONTENT.replace("Line 5\n", "Line five\n"));

    rebaseChangeOn(changeId, commit2);
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME, renamedFilePath);
    gApi.changes().id(changeId).edit().publish();
    Function<String, String> contentModification =
        fileContent -> fileContent.replace("Line 50\n", "Line fifty\n");
    addModifiedPatchSet(changeId, renamedFilePath, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, renamedFilePath).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 5");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line five");
    assertThat(diffInfo).content().element(1).isDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("Line 50");
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("Line fifty");
    assertThat(diffInfo).content().element(3).isNotDueToRebase();
    assertThat(diffInfo).content().element(4).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(renamedFilePath)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(renamedFilePath)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void renamedFileWithOnlyRebaseHunksIsIdentified_WhenRenamedBetweenPatchSets()
      throws Exception {
    String newFilePath1 = "renamed_some_file.txt";
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME, newFilePath1);
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    // Revert the renaming to be able to rebase.
    gApi.changes().id(changeId).edit().renameFile(newFilePath1, FILE_NAME);
    gApi.changes().id(changeId).edit().publish();

    ObjectId commit2 =
        addCommit(commit1, FILE_NAME, FILE_CONTENT.replace("Line 5\n", "Line five\n"));

    rebaseChangeOn(changeId, commit2);
    String newFilePath2 = "renamed_some_file_to_something_else.txt";
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME, newFilePath2);
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, newFilePath2);
    assertThat(changedFiles.get(newFilePath2)).linesInserted().isNull();
    assertThat(changedFiles.get(newFilePath2)).linesDeleted().isNull();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, newFilePath2).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 5");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line five");
    assertThat(diffInfo).content().element(1).isDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
  }

  @Test
  public void renamedFileWithOnlyRebaseHunksIsIdentified_WhenRenamedForRebaseAndForPatchSets()
      throws Exception {
    String newFilePath1 = "renamed_some_file.txt";
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME, newFilePath1);
    gApi.changes().id(changeId).edit().publish();
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    // Revert the renaming to be able to rebase.
    gApi.changes().id(changeId).edit().renameFile(newFilePath1, FILE_NAME);
    gApi.changes().id(changeId).edit().publish();

    ObjectId commit2 =
        addCommit(commit1, FILE_NAME, FILE_CONTENT.replace("Line 5\n", "Line five\n"));
    String newFilePath2 = "renamed_some_file_during_rebase.txt";
    ObjectId commit3 = addCommitRenamingFile(commit2, FILE_NAME, newFilePath2);

    rebaseChangeOn(changeId, commit3);
    String newFilePath3 = "renamed_some_file_to_something_else.txt";
    gApi.changes().id(changeId).edit().renameFile(newFilePath2, newFilePath3);
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, newFilePath3);
    assertThat(changedFiles.get(newFilePath3)).linesInserted().isNull();
    assertThat(changedFiles.get(newFilePath3)).linesDeleted().isNull();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, newFilePath3).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 5");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line five");
    assertThat(diffInfo).content().element(1).isDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
  }

  @Test
  public void copiedAndRenamedFilesWithOnlyRebaseHunksAreIdentified() throws Exception {
    String newFileContent = FILE_CONTENT.replace("Line 5\n", "Line five\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    // Copies are only identified by JGit when paired with renaming.
    String copyFileName = "copy_of_some_file.txt";
    String renamedFileName = "renamed_some_file.txt";
    gApi.changes()
        .id(changeId)
        .edit()
        .modifyFile(copyFileName, RawInputUtil.create(newFileContent));
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME, renamedFileName);
    gApi.changes().id(changeId).edit().publish();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(initialPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, copyFileName, renamedFileName);

    DiffInfo renamedFileDiffInfo =
        getDiffRequest(changeId, CURRENT, renamedFileName).withBase(initialPatchSetId).get();
    assertThat(renamedFileDiffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(renamedFileDiffInfo).content().element(1).linesOfA().containsExactly("Line 5");
    assertThat(renamedFileDiffInfo).content().element(1).linesOfB().containsExactly("Line five");
    assertThat(renamedFileDiffInfo).content().element(1).isDueToRebase();
    assertThat(renamedFileDiffInfo).content().element(2).commonLines().isNotEmpty();

    DiffInfo copiedFileDiffInfo =
        getDiffRequest(changeId, CURRENT, copyFileName).withBase(initialPatchSetId).get();
    assertThat(copiedFileDiffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(copiedFileDiffInfo).content().element(1).linesOfA().containsExactly("Line 5");
    assertThat(copiedFileDiffInfo).content().element(1).linesOfB().containsExactly("Line five");
    assertThat(copiedFileDiffInfo).content().element(1).isDueToRebase();
    assertThat(copiedFileDiffInfo).content().element(2).commonLines().isNotEmpty();
  }

  /*
   *                change PS B
   *                   |
   * change PS A    commit4
   *    |              |
   * commit2        commit3
   *    |             /
   * commit1 --------
   */
  @Test
  public void rebaseHunksWhenRebasingOnAnotherChangeOrPatchSetAreIdentified() throws Exception {
    ObjectId commit2 =
        addCommit(commit1, FILE_NAME, FILE_CONTENT.replace("Line 5\n", "Line five\n"));
    rebaseChangeOn(changeId, commit2);
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    String commit3FileContent = FILE_CONTENT.replace("Line 35\n", "Line thirty five\n");
    ObjectId commit3 = addCommit(commit1, FILE_NAME, commit3FileContent);
    ObjectId commit4 =
        addCommit(commit3, FILE_NAME, commit3FileContent.replace("Line 60\n", "Line sixty\n"));

    rebaseChangeOn(changeId, commit4);
    Function<String, String> contentModification =
        fileContent -> fileContent.replace("Line 20\n", "Line twenty\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line five");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line 5");
    assertThat(diffInfo).content().element(1).isDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("Line 20");
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("Line twenty");
    assertThat(diffInfo).content().element(3).isNotDueToRebase();
    assertThat(diffInfo).content().element(4).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(5).linesOfA().containsExactly("Line 35");
    assertThat(diffInfo).content().element(5).linesOfB().containsExactly("Line thirty five");
    assertThat(diffInfo).content().element(5).isDueToRebase();
    assertThat(diffInfo).content().element(6).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(7).linesOfA().containsExactly("Line 60");
    assertThat(diffInfo).content().element(7).linesOfB().containsExactly("Line sixty");
    assertThat(diffInfo).content().element(7).isDueToRebase();
    assertThat(diffInfo).content().element(8).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  /*
   *                change PS B
   *                   |
   * change PS A    commit4
   *    |              |
   * commit2        commit3
   *    |             /
   * commit1 --------
   */
  @Test
  public void unrelatedFileWhenRebasingOnAnotherChangeOrPatchSetIsIgnored() throws Exception {
    ObjectId commit2 =
        addCommit(commit1, FILE_NAME, FILE_CONTENT.replace("Line 5\n", "Line five\n"));
    rebaseChangeOn(changeId, commit2);
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    ObjectId commit3 =
        addCommit(commit1, FILE_NAME2, FILE_CONTENT2.replace("2nd line\n", "Second line\n"));
    ObjectId commit4 =
        addCommit(commit3, FILE_NAME, FILE_CONTENT.replace("Line 60\n", "Line sixty\n"));

    rebaseChangeOn(changeId, commit4);
    Function<String, String> contentModification =
        fileContent -> fileContent.replace("Line 20\n", "Line twenty\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.keySet()).containsExactly(COMMIT_MSG, FILE_NAME);
  }

  @Test
  public void rebaseHunksWhenReversingPatchSetOrderAreIdentified() throws Exception {
    ObjectId commit2 =
        addCommit(
            commit1,
            FILE_NAME,
            FILE_CONTENT.replace("Line 5\n", "Line five\n").replace("Line 35\n", ""));

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification =
        fileContent -> fileContent.replace("Line 20\n", "Line twenty\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    String currentPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    DiffInfo diffInfo =
        getDiffRequest(changeId, initialPatchSetId, FILE_NAME).withBase(currentPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line five");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line 5");
    assertThat(diffInfo).content().element(1).isDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("Line twenty");
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("Line 20");
    assertThat(diffInfo).content().element(3).isNotDueToRebase();
    assertThat(diffInfo).content().element(4).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(5).linesOfA().isNull();
    assertThat(diffInfo).content().element(5).linesOfB().containsExactly("Line 35");
    assertThat(diffInfo).content().element(5).isDueToRebase();
    assertThat(diffInfo).content().element(6).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).revision(initialPatchSetId).files(currentPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void intralineEditsInNonRebaseHunksAreIdentified() throws Exception {
    assume().that(intraline).isTrue();

    Function<String, String> contentModification =
        fileContent -> fileContent.replace("Line 1\n", "Line one\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).linesOfA().containsExactly("Line 1");
    assertThat(diffInfo).content().element(0).linesOfB().containsExactly("Line one");
    assertThat(diffInfo)
        .content()
        .element(0)
        .intralineEditsOfA()
        .containsExactly(ImmutableList.of(5, 1));
    assertThat(diffInfo)
        .content()
        .element(0)
        .intralineEditsOfB()
        .containsExactly(ImmutableList.of(5, 3));
    assertThat(diffInfo).content().element(0).isNotDueToRebase();
    assertThat(diffInfo).content().element(1).commonLines().isNotEmpty();
  }

  @Test
  public void intralineEditsInRebaseHunksAreIdentified() throws Exception {
    assume().that(intraline).isTrue();

    String newFileContent = FILE_CONTENT.replace("Line 1\n", "Line one\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, newFileContent);

    rebaseChangeOn(changeId, commit2);
    Function<String, String> contentModification =
        fileContent -> fileContent.replace("Line 50\n", "Line fifty\n");
    addModifiedPatchSet(changeId, FILE_NAME, contentModification);

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(initialPatchSetId).get();
    assertThat(diffInfo).content().element(0).linesOfA().containsExactly("Line 1");
    assertThat(diffInfo).content().element(0).linesOfB().containsExactly("Line one");
    assertThat(diffInfo)
        .content()
        .element(0)
        .intralineEditsOfA()
        .containsExactly(ImmutableList.of(5, 1));
    assertThat(diffInfo)
        .content()
        .element(0)
        .intralineEditsOfB()
        .containsExactly(ImmutableList.of(5, 3));
    assertThat(diffInfo).content().element(0).isDueToRebase();
    assertThat(diffInfo).content().element(1).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(2).linesOfA().containsExactly("Line 50");
    assertThat(diffInfo).content().element(2).linesOfB().containsExactly("Line fifty");
    assertThat(diffInfo).content().element(2).isNotDueToRebase();
    assertThat(diffInfo).content().element(3).commonLines().isNotEmpty();
  }

  @Test
  public void closeNonRebaseHunksAreCombinedForIntralineOptimizations() throws Exception {
    assume().that(intraline).isTrue();

    String fileContent = FILE_CONTENT.replace("Line 5\n", "{\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, fileContent);
    rebaseChangeOn(changeId, commit2);
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    addModifiedPatchSet(
        changeId,
        FILE_NAME,
        content -> content.replace("Line 4\n", "Line four\n").replace("Line 6\n", "Line six\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 4", "{", "Line 6");
    assertThat(diffInfo)
        .content()
        .element(1)
        .linesOfB()
        .containsExactly("Line four", "{", "Line six");
    assertThat(diffInfo).content().element(1).isNotDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    // Lines which weren't modified but are included in a hunk due to optimization don't count for
    // the number of inserted/deleted lines.
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(2);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(2);
  }

  @Test
  public void closeRebaseHunksAreNotCombinedForIntralineOptimizations() throws Exception {
    assume().that(intraline).isTrue();

    String fileContent = FILE_CONTENT.replace("Line 5\n", "{\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, fileContent);
    rebaseChangeOn(changeId, commit2);
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    String newFileContent =
        fileContent.replace("Line 4\n", "Line four\n").replace("Line 6\n", "Line six\n");
    ObjectId commit3 = addCommit(commit1, FILE_NAME, newFileContent);
    rebaseChangeOn(changeId, commit3);

    addModifiedPatchSet(
        changeId, FILE_NAME, content -> content.replace("Line 20\n", "Line twenty\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 4");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line four");
    assertThat(diffInfo).content().element(1).isDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("Line 6");
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("Line six");
    assertThat(diffInfo).content().element(3).isDueToRebase();
    assertThat(diffInfo).content().element(4).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(5).linesOfA().containsExactly("Line 20");
    assertThat(diffInfo).content().element(5).linesOfB().containsExactly("Line twenty");
    assertThat(diffInfo).content().element(5).isNotDueToRebase();
    assertThat(diffInfo).content().element(6).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void closeRebaseAndNonRebaseHunksAreNotCombinedForIntralineOptimizations()
      throws Exception {
    assume().that(intraline).isTrue();

    String fileContent = FILE_CONTENT.replace("Line 5\n", "{\n").replace("Line 7\n", "{\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, fileContent);
    rebaseChangeOn(changeId, commit2);
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    String newFileContent =
        fileContent.replace("Line 4\n", "Line four\n").replace("Line 8\n", "Line eight\n");
    ObjectId commit3 = addCommit(commit1, FILE_NAME, newFileContent);
    rebaseChangeOn(changeId, commit3);

    addModifiedPatchSet(changeId, FILE_NAME, content -> content.replace("Line 6\n", "Line six\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 4");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line four");
    assertThat(diffInfo).content().element(1).isDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("Line 6");
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("Line six");
    assertThat(diffInfo).content().element(3).isNotDueToRebase();
    assertThat(diffInfo).content().element(4).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(5).linesOfA().containsExactly("Line 8");
    assertThat(diffInfo).content().element(5).linesOfB().containsExactly("Line eight");
    assertThat(diffInfo).content().element(5).isDueToRebase();
    assertThat(diffInfo).content().element(6).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(1);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(1);
  }

  @Test
  public void closeNonRebaseHunksNextToRebaseHunksAreCombinedForIntralineOptimizations()
      throws Exception {
    assume().that(intraline).isTrue();

    String fileContent = FILE_CONTENT.replace("Line 5\n", "{\n").replace("Line 7\n", "{\n");
    ObjectId commit2 = addCommit(commit1, FILE_NAME, fileContent);
    rebaseChangeOn(changeId, commit2);
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;

    String newFileContent = fileContent.replace("Line 8\n", "Line eight!\n");
    ObjectId commit3 = addCommit(commit1, FILE_NAME, newFileContent);
    rebaseChangeOn(changeId, commit3);

    addModifiedPatchSet(
        changeId,
        FILE_NAME,
        content -> content.replace("Line 4\n", "Line four\n").replace("Line 6\n", "Line six\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME).withBase(previousPatchSetId).get();
    assertThat(diffInfo).content().element(0).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 4", "{", "Line 6");
    assertThat(diffInfo)
        .content()
        .element(1)
        .linesOfB()
        .containsExactly("Line four", "{", "Line six");
    assertThat(diffInfo).content().element(1).isNotDueToRebase();
    assertThat(diffInfo).content().element(2).commonLines().isNotEmpty();
    assertThat(diffInfo).content().element(3).linesOfA().containsExactly("Line 8");
    assertThat(diffInfo).content().element(3).linesOfB().containsExactly("Line eight!");
    assertThat(diffInfo).content().element(3).isDueToRebase();
    assertThat(diffInfo).content().element(4).commonLines().isNotEmpty();

    Map<String, FileInfo> changedFiles =
        gApi.changes().id(changeId).current().files(previousPatchSetId);
    assertThat(changedFiles.get(FILE_NAME)).linesInserted().isEqualTo(2);
    assertThat(changedFiles.get(FILE_NAME)).linesDeleted().isEqualTo(2);
  }

  @Test
  public void diffOfUnmodifiedFileWithWholeFileContextReturnsFileContents() throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, content -> content.replace("Line 2\n", "Line two\n"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    addModifiedPatchSet(
        changeId, FILE_NAME2, content -> content.replace("2nd line\n", "Second line\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withBase(previousPatchSetId)
            .withContext(DiffPreferencesInfo.WHOLE_FILE_CONTEXT)
            .get();
    // We don't list the full file contents here as that is not the focus of this test.
    assertThat(diffInfo)
        .content()
        .element(0)
        .commonLines()
        .containsAtLeast("Line 1", "Line two", "Line 3", "Line 4", "Line 5")
        .inOrder();
  }

  @Test
  public void diffOfUnmodifiedFileWithCommentAndWholeFileContextReturnsFileContents()
      throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, content -> content.replace("Line 2\n", "Line two\n"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    CommentInput comment = createCommentInput(2, 0, 3, 0, "Should be 'Line 2'.");
    ReviewInput reviewInput = new ReviewInput();
    reviewInput.comments = ImmutableMap.of(FILE_NAME, ImmutableList.of(comment));
    gApi.changes().id(changeId).revision(previousPatchSetId).review(reviewInput);
    addModifiedPatchSet(
        changeId, FILE_NAME2, content -> content.replace("2nd line\n", "Second line\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withBase(previousPatchSetId)
            .withContext(DiffPreferencesInfo.WHOLE_FILE_CONTEXT)
            .get();
    // We don't list the full file contents here as that is not the focus of this test.
    assertThat(diffInfo)
        .content()
        .element(0)
        .commonLines()
        .containsAtLeast("Line 1", "Line two", "Line 3", "Line 4", "Line 5")
        .inOrder();
  }

  @Test
  public void diffOfNonExistentFileIsAnEmptyDiffResult() throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, content -> content.replace("Line 2\n", "Line two\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, "a_non-existent_file.txt")
            .withBase(initialPatchSetId)
            .withContext(DiffPreferencesInfo.WHOLE_FILE_CONTEXT)
            .get();
    assertThat(diffInfo).content().isEmpty();
  }

  // This behavior is likely a bug. A fix might not be easy as it might break syntax highlighting.
  // TODO: Fix this issue or remove the broken parameter (at least in the documentation).
  @Test
  public void contextParameterIsIgnored() throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, content -> content.replace("Line 20\n", "Line twenty\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withBase(initialPatchSetId)
            .withContext(5)
            .get();
    assertThat(diffInfo).content().element(0).commonLines().hasSize(19);
    assertThat(diffInfo).content().element(1).linesOfA().containsExactly("Line 20");
    assertThat(diffInfo).content().element(1).linesOfB().containsExactly("Line twenty");
    assertThat(diffInfo).content().element(2).commonLines().hasSize(81);
  }

  // This behavior is likely a bug. A fix might not be easy as it might break syntax highlighting.
  // TODO: Fix this issue or remove the broken parameter (at least in the documentation).
  @Test
  public void contextParameterIsIgnoredForUnmodifiedFileWithComment() throws Exception {
    addModifiedPatchSet(
        changeId, FILE_NAME, content -> content.replace("Line 20\n", "Line twenty\n"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    CommentInput comment = createCommentInput(20, 0, 21, 0, "Should be 'Line 20'.");
    ReviewInput reviewInput = new ReviewInput();
    reviewInput.comments = ImmutableMap.of(FILE_NAME, ImmutableList.of(comment));
    gApi.changes().id(changeId).revision(previousPatchSetId).review(reviewInput);
    addModifiedPatchSet(
        changeId, FILE_NAME2, content -> content.replace("2nd line\n", "Second line\n"));

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withBase(previousPatchSetId)
            .withContext(5)
            .get();
    assertThat(diffInfo).content().element(0).commonLines().hasSize(101);
  }

  @Test
  public void requestingDiffForOldFileNameOfRenamedFileYieldsReasonableResult() throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, content -> content.replace("Line 2\n", "Line two\n"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    String newFilePath = "a_new_file.txt";
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME, newFilePath);
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withBase(previousPatchSetId)
            .withContext(DiffPreferencesInfo.WHOLE_FILE_CONTEXT)
            .get();
    // This behavior has been present in Gerrit for quite some time. It differs from the results
    // returned for other cases (e.g. requesting the diff with whole file context for an unmodified
    // file; requesting the diff with whole file context for a non-existent file). However, it's not
    // completely clear what should be returned. The closest would be the result of a file deletion
    // but that might also be misleading for users as actually a file rename occurred. In fact,
    // requesting the diff result for the old file name of a renamed file is not a reasonable use
    // case at all. We at least guarantee that we don't run into an internal error.
    assertThat(diffInfo).content().element(0).commonLines().isNull();
    assertThat(diffInfo).content().element(0).numberOfSkippedLines().isGreaterThan(0);
  }

  @Test
  public void requestingDiffForOldFileNameOfRenamedFileWithCommentOnOldFileYieldsReasonableResult()
      throws Exception {
    addModifiedPatchSet(changeId, FILE_NAME, content -> content.replace("Line 2\n", "Line two\n"));
    String previousPatchSetId = gApi.changes().id(changeId).get().currentRevision;
    CommentInput comment = createCommentInput(2, 0, 3, 0, "Should be 'Line 2'.");
    ReviewInput reviewInput = new ReviewInput();
    reviewInput.comments = ImmutableMap.of(FILE_NAME, ImmutableList.of(comment));
    gApi.changes().id(changeId).revision(previousPatchSetId).review(reviewInput);
    String newFilePath = "a_new_file.txt";
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME, newFilePath);
    gApi.changes().id(changeId).edit().publish();

    DiffInfo diffInfo =
        getDiffRequest(changeId, CURRENT, FILE_NAME)
            .withBase(previousPatchSetId)
            .withContext(DiffPreferencesInfo.WHOLE_FILE_CONTEXT)
            .get();
    // See comment for requestingDiffForOldFileNameOfRenamedFileYieldsReasonableResult().
    // This test should additionally ensure that we also don't run into an internal error when
    // a comment is present.
    assertThat(diffInfo).content().element(0).commonLines().isNull();
    assertThat(diffInfo).content().element(0).numberOfSkippedLines().isGreaterThan(0);
  }

  private static CommentInput createCommentInput(
      int startLine, int startCharacter, int endLine, int endCharacter, String message) {
    CommentInput comment = new CommentInput();
    comment.range = new Comment.Range();
    comment.range.startLine = startLine;
    comment.range.startCharacter = startCharacter;
    comment.range.endLine = endLine;
    comment.range.endCharacter = endCharacter;
    comment.message = message;
    return comment;
  }

  private void assertDiffForNewFile(
      PushOneCommit.Result pushResult, String path, String expectedContentSideB) throws Exception {
    DiffInfo diff =
        gApi.changes()
            .id(pushResult.getChangeId())
            .revision(pushResult.getCommit().name())
            .file(path)
            .diff();

    List<String> headers = new ArrayList<>();
    if (path.equals(COMMIT_MSG)) {
      RevCommit c = pushResult.getCommit();

      RevCommit parentCommit = c.getParents()[0];
      String parentCommitId =
          abbreviateName(parentCommit, 8, testRepo.getRevWalk().getObjectReader());
      headers.add("Parent:     " + parentCommitId + " (" + parentCommit.getShortMessage() + ")");

      SimpleDateFormat dtfmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
      PersonIdent author = c.getAuthorIdent();
      dtfmt.setTimeZone(author.getTimeZone());
      headers.add("Author:     " + author.getName() + " <" + author.getEmailAddress() + ">");
      headers.add("AuthorDate: " + dtfmt.format(author.getWhen().getTime()));

      PersonIdent committer = c.getCommitterIdent();
      dtfmt.setTimeZone(committer.getTimeZone());
      headers.add("Commit:     " + committer.getName() + " <" + committer.getEmailAddress() + ">");
      headers.add("CommitDate: " + dtfmt.format(committer.getWhen().getTime()));
      headers.add("");
    }

    if (!headers.isEmpty()) {
      String header = Joiner.on("\n").join(headers);
      expectedContentSideB = header + "\n" + expectedContentSideB;
    }

    assertDiffForNewFile(diff, pushResult.getCommit(), path, expectedContentSideB);
  }

  private void rebaseChangeOn(String changeId, ObjectId newParent) throws Exception {
    RebaseInput rebaseInput = new RebaseInput();
    rebaseInput.base = newParent.getName();
    gApi.changes().id(changeId).current().rebase(rebaseInput);
  }

  private ObjectId addCommit(ObjectId parentCommit, String filePath, String fileContent)
      throws Exception {
    ImmutableMap<String, String> files = ImmutableMap.of(filePath, fileContent);
    return addCommit(parentCommit, files);
  }

  private ObjectId addCommit(ObjectId parentCommit, ImmutableMap<String, String> files)
      throws Exception {
    testRepo.reset(parentCommit);
    PushOneCommit push =
        pushFactory.create(admin.newIdent(), testRepo, "Adjust files of repo", files);
    PushOneCommit.Result result = push.to("refs/for/master");
    return result.getCommit();
  }

  private ObjectId addCommit(ObjectId parentCommit, String filePath, byte[] fileContent)
      throws Exception {
    testRepo.reset(parentCommit);
    PushOneCommit.Result result = createEmptyChange();
    String changeId = result.getChangeId();
    gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(fileContent));
    gApi.changes().id(changeId).edit().publish();
    String currentRevision = gApi.changes().id(changeId).get().currentRevision;
    GitUtil.fetch(testRepo, "refs/*:refs/*");
    return ObjectId.fromString(currentRevision);
  }

  private ObjectId addCommitRemovingFiles(ObjectId parentCommit, String... removedFilePaths)
      throws Exception {
    testRepo.reset(parentCommit);
    Map<String, String> files =
        Arrays.stream(removedFilePaths)
            .collect(toMap(Function.identity(), path -> "Irrelevant content"));
    PushOneCommit push =
        pushFactory.create(admin.newIdent(), testRepo, "Remove files from repo", files);
    PushOneCommit.Result result = push.rm("refs/for/master");
    return result.getCommit();
  }

  private ObjectId addCommitRenamingFile(
      ObjectId parentCommit, String oldFilePath, String newFilePath) throws Exception {
    testRepo.reset(parentCommit);
    PushOneCommit.Result result = createEmptyChange();
    String changeId = result.getChangeId();
    gApi.changes().id(changeId).edit().renameFile(oldFilePath, newFilePath);
    gApi.changes().id(changeId).edit().publish();
    String currentRevision = gApi.changes().id(changeId).get().currentRevision;
    GitUtil.fetch(testRepo, "refs/*:refs/*");
    return ObjectId.fromString(currentRevision);
  }

  private Result createEmptyChange() throws Exception {
    PushOneCommit push =
        pushFactory.create(admin.newIdent(), testRepo, "Test change", ImmutableMap.of());
    return push.to("refs/for/master");
  }

  private void addModifiedPatchSet(
      String changeId, String filePath, Function<String, String> contentModification)
      throws Exception {
    try (BinaryResult content = gApi.changes().id(changeId).current().file(filePath).content()) {
      String newContent = contentModification.apply(content.asString());
      gApi.changes().id(changeId).edit().modifyFile(filePath, RawInputUtil.create(newContent));
    }
    gApi.changes().id(changeId).edit().publish();
  }

  private static byte[] createRgbImage(int red, int green, int blue) throws IOException {
    BufferedImage bufferedImage = new BufferedImage(10, 20, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < bufferedImage.getWidth(); x++) {
      for (int y = 0; y < bufferedImage.getHeight(); y++) {
        int rgb = (red << 16) + (green << 8) + blue;
        bufferedImage.setRGB(x, y, rgb);
      }
    }

    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
    return byteArrayOutputStream.toByteArray();
  }

  private FileApi.DiffRequest getDiffRequest(String changeId, String revisionId, String fileName)
      throws Exception {
    return gApi.changes()
        .id(changeId)
        .revision(revisionId)
        .file(fileName)
        .diffRequest()
        .withIntraline(intraline);
  }
}
