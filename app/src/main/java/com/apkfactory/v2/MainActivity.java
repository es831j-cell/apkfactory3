package com.apkfactory.v2;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import android.util.Base64;

public class MainActivity extends Activity {
    private static final int PICK_ZIP = 1001;
    private static final int SAVE_APK = 1002;
    private static final String API = "https://api.github.com";

    private EditText repoName, tokenField;
    private Switch privateSwitch;
    private Button chooseZip, buildButton, saveButton, openRepoButton, forgetTokenButton;
    private TextView chosenFile, status, buildLog, tokenStatus;
    private ProgressBar progress;
    private Uri projectZipUri;
    private byte[] pendingApk;
    private String pendingApkName = "app-debug.apk";
    private String repoUrl;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SecureTokenStore tokenStore;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        tokenStore = new SecureTokenStore(this);
        buildUi();
        loadSavedToken();
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(Color.rgb(40,47,52));
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setPadding(0, dp(5), 0, dp(5));
        return v;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(28));
        root.setBackgroundColor(Color.rgb(248,249,250));
        sv.addView(root);

        TextView title = text("APK Factory", 34, true); root.addView(title);
        TextView sub = text("Android project ZIP → GitHub repository → GitHub Actions → downloadable APK", 18, false);
        sub.setTextColor(Color.rgb(85,93,99)); root.addView(sub);
        space(root, 14);

        chooseZip = new Button(this); chooseZip.setText("Choose Android Project ZIP"); chooseZip.setTextSize(17); root.addView(chooseZip, full(58));
        chosenFile = text("No ZIP selected", 15, false); chosenFile.setTextColor(Color.DKGRAY); root.addView(chosenFile);
        space(root, 8);

        root.addView(text("Repository name", 16, false));
        repoName = new EditText(this); repoName.setTextSize(19); repoName.setSingleLine(true); repoName.setHint("My-Android-App"); root.addView(repoName, full(52));

        privateSwitch = new Switch(this); privateSwitch.setText("Private GitHub repository"); privateSwitch.setTextSize(16); privateSwitch.setChecked(true); root.addView(privateSwitch, full(52));

        root.addView(text("GitHub personal access token", 16, false));
        tokenField = new EditText(this); tokenField.setSingleLine(true); tokenField.setTextSize(18);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenField.setHint("github_pat_… or ghp_…"); root.addView(tokenField, full(52));
        tokenStatus = text("Token is encrypted with Android Keystore and remembered only on this device.", 13, false);
        tokenStatus.setTextColor(Color.rgb(98,105,110)); root.addView(tokenStatus);
        forgetTokenButton = new Button(this); forgetTokenButton.setText("Forget saved token"); root.addView(forgetTokenButton, full(48));
        space(root, 8);

        buildButton = new Button(this); buildButton.setText("BUILD APK"); buildButton.setTextSize(20); buildButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD); root.addView(buildButton, full(62));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgress(0); root.addView(progress, full(16));
        status = text("Ready", 19, true); root.addView(status);

        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        saveButton = new Button(this); saveButton.setText("Save APK"); saveButton.setEnabled(false);
        openRepoButton = new Button(this); openRepoButton.setText("Open Repo"); openRepoButton.setEnabled(false);
        buttons.addView(saveButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        spaceH(buttons, 10);
        buttons.addView(openRepoButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(buttons);

        root.addView(text("Build log", 16, false));
        buildLog = text("Waiting for a project…", 14, false); buildLog.setTypeface(Typeface.MONOSPACE); buildLog.setTextIsSelectable(true);
        buildLog.setBackgroundColor(Color.WHITE); buildLog.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(buildLog, fullWrap());

        setContentView(sv);
        chooseZip.setOnClickListener(v -> pickZip());
        buildButton.setOnClickListener(v -> startBuild());
        saveButton.setOnClickListener(v -> saveApk());
        openRepoButton.setOnClickListener(v -> { if (repoUrl != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))); });
        forgetTokenButton.setOnClickListener(v -> forgetToken());
    }

    private LinearLayout.LayoutParams full(int h) { return new LinearLayout.LayoutParams(-1, dp(h)); }
    private LinearLayout.LayoutParams fullWrap() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(20)); return p; }
    private void space(LinearLayout l, int h){ Space s=new Space(this); l.addView(s,new LinearLayout.LayoutParams(1,dp(h))); }
    private void spaceH(LinearLayout l, int w){ Space s=new Space(this); l.addView(s,new LinearLayout.LayoutParams(dp(w),1)); }
    private int dp(int n){ return (int)(n*getResources().getDisplayMetrics().density+0.5f); }

    private void loadSavedToken() {
        try {
            String t = tokenStore.load();
            if (t != null && !t.isEmpty()) {
                tokenField.setText(t);
                tokenStatus.setText("Saved token loaded securely from this device.");
            }
        } catch (Exception e) {
            tokenStatus.setText("Saved token could not be loaded. Enter it once to replace it.");
        }
    }

    private void forgetToken() {
        try { tokenStore.clear(); } catch (Exception ignored) {}
        tokenField.setText(""); tokenStatus.setText("Saved token removed from this device.");
        Toast.makeText(this, "Saved token removed", Toast.LENGTH_SHORT).show();
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/zip");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip","application/octet-stream"});
        startActivityForResult(i, PICK_ZIP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == PICK_ZIP) {
            projectZipUri = data.getData();
            try { getContentResolver().takePersistableUriPermission(projectZipUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            String name = queryName(projectZipUri);
            chosenFile.setText(name == null ? "Android project ZIP selected" : name);
            if (repoName.getText().toString().trim().isEmpty() && name != null) repoName.setText(cleanRepoName(name.replaceFirst("(?i)\\.zip$", "")));
            setStatus("Ready to build", 0);
        } else if (requestCode == SAVE_APK && pendingApk != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) { out.write(pendingApk); }
            catch (Exception e) { Toast.makeText(this, "Could not save APK: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }
    }

    private String queryName(Uri u) {
        android.database.Cursor c = null;
        try { c=getContentResolver().query(u,null,null,null,null); if(c!=null&&c.moveToFirst()){int ix=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if(ix>=0)return c.getString(ix);} } catch(Exception ignored){} finally { if(c!=null)c.close(); }
        return null;
    }
    private String cleanRepoName(String s){ return s.replaceAll("[^A-Za-z0-9._-]+","-").replaceAll("^-+|-+$",""); }

    private void startBuild() {
        if (projectZipUri == null) { toast("Choose an Android project ZIP first."); return; }
        String repo = cleanRepoName(repoName.getText().toString().trim());
        String token = tokenField.getText().toString().trim();
        if (repo.isEmpty()) { toast("Enter a repository name."); return; }
        if (token.length() < 20) { toast("Enter a valid GitHub personal access token."); return; }
        repoName.setText(repo);
        buildButton.setEnabled(false); saveButton.setEnabled(false); openRepoButton.setEnabled(false); pendingApk=null; repoUrl=null;
        logClear();
        io.submit(() -> {
            try {
                tokenStore.save(token);
                ui(() -> tokenStatus.setText("Token securely saved on this device."));
                setStatus("Reading project", 5);
                List<ProjectFile> files = readZip(projectZipUri);
                log("Project looks buildable: " + files.size() + " files.");
                validateProject(files);

                setStatus("Connecting to GitHub", 10);
                String owner = githubUser(token);
                log("GitHub account: " + owner);
                JSONObject repoJson = createRepo(token, repo, privateSwitch.isChecked());
                repoUrl = repoJson.optString("html_url", "https://github.com/"+owner+"/"+repo);
                ui(() -> openRepoButton.setEnabled(true));
                log("Repository created: " + owner + "/" + repo);

                boolean workflowPresent = false;
                for (ProjectFile f : files) {
                    if (f.path.startsWith(".github/workflows/") && (f.path.endsWith(".yml")||f.path.endsWith(".yaml"))) workflowPresent=true;
                }
                if (!workflowPresent) {
                    log("No GitHub Actions workflow found. Adding a standard Android build workflow.");
                    files.add(new ProjectFile(".github/workflows/android-build.yml", defaultWorkflow().getBytes(StandardCharsets.UTF_8)));
                }

                String branch = repoJson.optString("default_branch", "main");
                setStatus("Preparing one project commit", 15);
                String commitSha = uploadProjectSingleCommit(token, owner, repo, branch, files);
                log("Entire project committed in one Git commit: " + commitSha.substring(0, Math.min(7, commitSha.length())));
                log("GitHub Actions will start once, after the complete project is present.");
                setStatus("Waiting for GitHub Actions", 72);

                long runId = waitForRun(token, owner, repo, commitSha);
                log("Build started: run #" + runId);
                JSONObject run = waitForCompletion(token, owner, repo, runId);
                String conclusion = run.optString("conclusion", "unknown");
                if (!"success".equals(conclusion)) throw new Exception("GitHub Actions finished with: " + conclusion + ". Tap Open Repo to inspect the build log.");
                setStatus("Downloading APK artifact", 94);
                log("Build succeeded. Waiting for GitHub to publish the APK artifact…");
                Artifact a = waitForArtifact(token, owner, repo, runId);
                byte[] artifactZip = getBytes(a.downloadUrl, token);
                ApkFile apk = extractFirstApk(artifactZip);
                pendingApk = apk.bytes; pendingApkName = apk.name;
                log("Build succeeded. APK ready: " + apk.name);
                setStatus("Build complete", 100);
                ui(() -> saveButton.setEnabled(true));
            } catch (Exception e) {
                log("ERROR: " + safeMessage(e));
                setStatus("Build stopped", 0);
            } finally {
                ui(() -> buildButton.setEnabled(true));
            }
        });
    }

    private String safeMessage(Exception e){ String m=e.getMessage(); return (m==null||m.trim().isEmpty())?e.getClass().getSimpleName():m; }

    private void saveApk() {
        if (pendingApk == null) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.android.package-archive"); i.putExtra(Intent.EXTRA_TITLE, pendingApkName);
        startActivityForResult(i, SAVE_APK);
    }

    private List<ProjectFile> readZip(Uri uri) throws Exception {
        List<ProjectFile> raw = new ArrayList<>();
        long total=0;
        try (InputStream in=getContentResolver().openInputStream(uri); ZipInputStream zis=new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry ze;
            while((ze=zis.getNextEntry())!=null){
                if(ze.isDirectory()) continue;
                String path=ze.getName().replace('\\','/');
                if(path.startsWith("/")||path.contains("../")) continue;
                if(path.startsWith("__MACOSX/")||path.contains("/.git/")||path.startsWith(".git/")||path.contains("/build/")) continue;
                ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[16384]; int r;
                while((r=zis.read(buf))!=-1){out.write(buf,0,r); total+=r; if(total>120L*1024*1024) throw new Exception("Project ZIP is too large for this APK Factory build (120 MB source limit).");}
                raw.add(new ProjectFile(path,out.toByteArray()));
            }
        }
        if(raw.isEmpty()) throw new Exception("ZIP contains no project files.");
        String prefix = commonTopFolder(raw);
        if(prefix!=null){ for(ProjectFile f:raw) f.path=f.path.substring(prefix.length()); }
        raw.removeIf(f -> f.path.trim().isEmpty());
        return raw;
    }

    private String commonTopFolder(List<ProjectFile> files){
        String first=files.get(0).path; int slash=first.indexOf('/'); if(slash<1)return null; String top=first.substring(0,slash+1);
        for(ProjectFile f:files) if(!f.path.startsWith(top)) return null; return top;
    }

    private void validateProject(List<ProjectFile> files) throws Exception {
        boolean gradle=false, app=false;
        for(ProjectFile f:files){
            String p=f.path;
            if(p.equals("settings.gradle")||p.equals("settings.gradle.kts")||p.equals("build.gradle")||p.equals("build.gradle.kts")) gradle=true;
            if(p.equals("app/build.gradle")||p.equals("app/build.gradle.kts")) app=true;
        }
        if(!gradle&&!app) throw new Exception("This ZIP does not look like a Gradle Android project. Expected settings.gradle or app/build.gradle.");
    }

    private String githubUser(String token) throws Exception { return requestJson("GET", API+"/user", token, null).getString("login"); }

    private JSONObject createRepo(String token,String repo,boolean priv) throws Exception {
        JSONObject body=new JSONObject(); body.put("name",repo); body.put("private",priv); body.put("auto_init",true);
        try { return requestJson("POST", API+"/user/repos", token, body.toString()); }
        catch(HttpError h){ if(h.code==422) throw new Exception("GitHub could not create that repository. A repo with this name may already exist. Choose a new repository name."); throw h; }
    }

    private String uploadProjectSingleCommit(String token,String owner,String repo,String branch,List<ProjectFile> files) throws Exception {
        // The repository is initialized with a tiny README commit. Every project file is then
        // added to one Git tree and the branch ref is advanced exactly once. This prevents a
        // push-triggered workflow from running against half-uploaded projects.
        JSONObject ref = null;
        Exception last = null;
        for (int attempt=0; attempt<12; attempt++) {
            try {
                ref = requestJson("GET", API+"/repos/"+enc(owner)+"/"+enc(repo)+"/git/ref/heads/"+enc(branch), token, null);
                break;
            } catch (Exception e) {
                last = e; Thread.sleep(1000);
            }
        }
        if (ref == null) throw new Exception("GitHub repository initialized, but its default branch was not ready: " + (last==null?"unknown error":safeMessage(last)));

        String baseCommitSha = ref.getJSONObject("object").getString("sha");
        JSONObject baseCommit = requestJson("GET", API+"/repos/"+enc(owner)+"/"+enc(repo)+"/git/commits/"+enc(baseCommitSha), token, null);
        String baseTreeSha = baseCommit.getJSONObject("tree").getString("sha");

        JSONArray entries = new JSONArray();
        int n = 0;
        for (ProjectFile f : files) {
            JSONObject blobBody = new JSONObject();
            blobBody.put("content", Base64.encodeToString(f.data, Base64.NO_WRAP));
            blobBody.put("encoding", "base64");
            JSONObject blob = requestJson("POST", API+"/repos/"+enc(owner)+"/"+enc(repo)+"/git/blobs", token, blobBody.toString());

            JSONObject entry = new JSONObject();
            entry.put("path", f.path);
            entry.put("mode", f.path.equals("gradlew") ? "100755" : "100644");
            entry.put("type", "blob");
            entry.put("sha", blob.getString("sha"));
            entries.put(entry);

            n++;
            int pct = 15 + (int)(48.0*n/Math.max(1,files.size()));
            setStatus("Uploading project data " + n + "/" + files.size(), pct);
        }

        JSONObject treeBody = new JSONObject();
        treeBody.put("base_tree", baseTreeSha);
        treeBody.put("tree", entries);
        JSONObject tree = requestJson("POST", API+"/repos/"+enc(owner)+"/"+enc(repo)+"/git/trees", token, treeBody.toString());

        JSONObject commitBody = new JSONObject();
        commitBody.put("message", "Import complete Android project");
        commitBody.put("tree", tree.getString("sha"));
        JSONArray parents = new JSONArray(); parents.put(baseCommitSha); commitBody.put("parents", parents);
        JSONObject commit = requestJson("POST", API+"/repos/"+enc(owner)+"/"+enc(repo)+"/git/commits", token, commitBody.toString());
        String commitSha = commit.getString("sha");

        JSONObject update = new JSONObject(); update.put("sha", commitSha); update.put("force", false);
        requestJson("PATCH", API+"/repos/"+enc(owner)+"/"+enc(repo)+"/git/refs/heads/"+enc(branch), token, update.toString());
        return commitSha;
    }

    private void uploadFile(String token,String owner,String repo,String path,byte[] data,String message) throws Exception {
        JSONObject body=new JSONObject(); body.put("message",message); body.put("content",Base64.encodeToString(data,Base64.NO_WRAP));
        requestJson("PUT", API+"/repos/"+enc(owner)+"/"+enc(repo)+"/contents/"+encodePath(path), token, body.toString());
    }

    private long waitForRun(String token,String owner,String repo,String commitSha) throws Exception {
        // More than one workflow can legitimately trigger for the same commit.
        // Return the newest candidate here, then waitForArtifact() will search ALL
        // completed runs for the commit if this candidate has no APK artifact.
        for(int i=0;i<36;i++){
            JSONObject j=requestJson("GET",API+"/repos/"+enc(owner)+"/"+enc(repo)+"/actions/runs?head_sha="+enc(commitSha)+"&per_page=30",token,null);
            JSONArray a=j.optJSONArray("workflow_runs");
            if(a!=null && a.length()>0){
                JSONObject run=a.getJSONObject(0);
                log("GitHub Actions candidate: "+run.optString("name","workflow")+" run #"+run.optLong("run_number",0));
                return run.getLong("id");
            }
            log("Waiting for GitHub Actions build to appear…"); Thread.sleep(5000);
        }
        throw new Exception("No GitHub Actions run appeared for the completed project commit. Open the repository and check the Actions tab.");
    }

    private JSONObject waitForCompletion(String token,String owner,String repo,long runId) throws Exception {
        for(int i=0;i<120;i++){
            JSONObject r=requestJson("GET",API+"/repos/"+enc(owner)+"/"+enc(repo)+"/actions/runs/"+runId,token,null);
            String st=r.optString("status"); if("completed".equals(st)) return r;
            setStatus("GitHub Actions: " + st, Math.min(93,76+i/5)); Thread.sleep(6000);
        }
        throw new Exception("GitHub Actions build timed out after about 12 minutes.");
    }

    private Artifact waitForArtifact(String token,String owner,String repo,long runId) throws Exception {
        // First try the exact run APK Factory followed.
        // If the project contains multiple workflows, GitHub may have completed a
        // different run for the same commit that owns the APK artifact. Search
        // repo-wide artifacts as a fallback and prefer APK-looking artifact names.
        for(int attempt=1; attempt<=30; attempt++){
            Artifact exact = artifactFromRun(token, owner, repo, runId);
            if(exact!=null) return exact;

            Artifact any = newestRepoArtifact(token, owner, repo);
            if(any!=null){
                log("Found APK artifact through repository fallback: "+any.name);
                return any;
            }

            if(attempt<30){
                log("APK artifact not visible yet. Searching exact run + repository… ("+attempt+"/30)");
                Thread.sleep(4000);
            }
        }
        throw new Exception("Build succeeded, but APK Factory could not find an Actions artifact after about 2 minutes. Check that the workflow uses actions/upload-artifact@v4 and uploads a .apk file.");
    }

    private Artifact artifactFromRun(String token,String owner,String repo,long runId) throws Exception {
        JSONObject j=requestJson("GET",API+"/repos/"+enc(owner)+"/"+enc(repo)+"/actions/runs/"+runId+"/artifacts?per_page=100",token,null);
        JSONArray a=j.optJSONArray("artifacts");
        return chooseArtifact(a);
    }

    private Artifact newestRepoArtifact(String token,String owner,String repo) throws Exception {
        JSONObject j=requestJson("GET",API+"/repos/"+enc(owner)+"/"+enc(repo)+"/actions/artifacts?per_page=100",token,null);
        JSONArray a=j.optJSONArray("artifacts");
        return chooseArtifact(a);
    }

    private Artifact chooseArtifact(JSONArray a) throws Exception {
        if(a==null || a.length()==0) return null;
        JSONObject fallback=null;
        for(int i=0;i<a.length();i++){
            JSONObject x=a.getJSONObject(i);
            if(x.optBoolean("expired",false)) continue;
            String n=x.optString("name","artifact");
            String lower=n.toLowerCase(Locale.US);
            if(lower.contains("apk") || lower.contains("lumi") || lower.contains("android"))
                return new Artifact(x.getString("archive_download_url"),n);
            if(fallback==null) fallback=x;
        }
        if(fallback!=null) return new Artifact(fallback.getString("archive_download_url"),fallback.optString("name","artifact"));
        return null;
    }

    private ApkFile extractFirstApk(byte[] zip) throws Exception {
        try(ZipInputStream zis=new ZipInputStream(new ByteArrayInputStream(zip))){ZipEntry ze; while((ze=zis.getNextEntry())!=null){if(!ze.isDirectory()&&ze.getName().toLowerCase(Locale.US).endsWith(".apk")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=zis.read(b))!=-1)out.write(b,0,n);String name=new File(ze.getName()).getName();return new ApkFile(name,out.toByteArray());}}}
        throw new Exception("Artifact downloaded, but it did not contain an APK.");
    }

    private JSONObject requestJson(String method,String url,String token,String body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(); c.setRequestMethod(method); c.setConnectTimeout(20000); c.setReadTimeout(45000);
        c.setRequestProperty("Accept","application/vnd.github+json"); c.setRequestProperty("Authorization","Bearer "+token); c.setRequestProperty("X-GitHub-Api-Version","2022-11-28"); c.setRequestProperty("User-Agent","APK-Factory-v2.3");
        if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}}
        int code=c.getResponseCode(); String txt=readText(code>=200&&code<300?c.getInputStream():c.getErrorStream());
        if(code<200||code>=300) throw new HttpError(code, githubError(code,txt));
        return txt.trim().isEmpty()?new JSONObject():new JSONObject(txt);
    }

    private byte[] getBytes(String url,String token) throws Exception {
        URL cur=new URL(url);
        for(int redirects=0;redirects<6;redirects++){
            HttpURLConnection c=(HttpURLConnection)cur.openConnection(); c.setInstanceFollowRedirects(false); c.setConnectTimeout(20000); c.setReadTimeout(60000);
            c.setRequestProperty("Accept","application/vnd.github+json"); c.setRequestProperty("Authorization","Bearer "+token); c.setRequestProperty("X-GitHub-Api-Version","2022-11-28"); c.setRequestProperty("User-Agent","APK-Factory-v2.3");
            int code=c.getResponseCode();
            if(code>=300&&code<400){String loc=c.getHeaderField("Location"); if(loc==null)throw new Exception("Artifact redirect did not include a location."); cur=new URL(loc); continue;}
            if(code<200||code>=300)throw new Exception("Artifact download failed: HTTP "+code);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}
        }
        throw new Exception("Too many redirects while downloading artifact.");
    }

    private String readText(InputStream in) throws Exception { if(in==null)return ""; try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=x.read(b))!=-1)o.write(b,0,n);return o.toString("UTF-8");} }
    private String githubError(int code,String txt){try{JSONObject j=new JSONObject(txt);return "GitHub HTTP "+code+": "+j.optString("message",txt);}catch(Exception e){return "GitHub HTTP "+code+": "+txt;}}
    private String enc(String s){ try{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(Exception e){return s;} }
    private String encodePath(String p){String[] a=p.split("/");StringBuilder b=new StringBuilder();for(int i=0;i<a.length;i++){if(i>0)b.append('/');b.append(enc(a[i]));}return b.toString();}

    private String defaultWorkflow(){ return "name: Build Android APK\n\non:\n  push:\n  workflow_dispatch:\n\npermissions:\n  contents: read\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n      - uses: actions/setup-java@v4\n        with:\n          distribution: temurin\n          java-version: '17'\n      - uses: gradle/actions/setup-gradle@v4\n        with:\n          gradle-version: '8.10.2'\n      - name: Build APK\n        shell: bash\n        run: |\n          if [ -f ./gradlew ]; then chmod +x ./gradlew && ./gradlew assembleDebug --stacktrace; else gradle assembleDebug --stacktrace; fi\n      - name: Upload APK\n        uses: actions/upload-artifact@v4\n        with:\n          name: android-apk\n          path: '**/build/outputs/apk/**/*.apk'\n          if-no-files-found: error\n"; }

    private void setStatus(String s,int pct){ui(()->{status.setText(s);progress.setProgress(pct);});}
    private void log(String s){ui(()->{String old=buildLog.getText().toString();if(old.equals("Waiting for a project…"))old="";buildLog.setText(old+(old.isEmpty()?"":"\n")+s);});}
    private void logClear(){ui(()->buildLog.setText(""));}
    private void ui(Runnable r){runOnUiThread(r);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    static class ProjectFile { String path; byte[] data; ProjectFile(String p,byte[]d){path=p;data=d;} }
    static class Artifact { String downloadUrl,name; Artifact(String u,String n){downloadUrl=u;name=n;} }
    static class ApkFile { String name; byte[] bytes; ApkFile(String n,byte[]b){name=n;bytes=b;} }
    static class HttpError extends Exception { int code; HttpError(int c,String m){super(m);code=c;} }

    static class SecureTokenStore {
        private static final String ALIAS="apk_factory_v2_github_pat";
        private static final String PREF="secure_token";
        private final Context ctx;
        SecureTokenStore(Context c){ctx=c.getApplicationContext();}
        private SecretKey key() throws Exception {
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
            if(ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
            KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
            return kg.generateKey();
        }
        void save(String token) throws Exception {
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key());
            byte[] iv=c.getIV(); byte[] enc=c.doFinal(token.getBytes(StandardCharsets.UTF_8));
            ctx.getSharedPreferences(PREF,MODE_PRIVATE).edit().putString("iv",Base64.encodeToString(iv,Base64.NO_WRAP)).putString("data",Base64.encodeToString(enc,Base64.NO_WRAP)).apply();
        }
        String load() throws Exception {
            android.content.SharedPreferences p=ctx.getSharedPreferences(PREF,MODE_PRIVATE); String ivs=p.getString("iv",null), ds=p.getString("data",null); if(ivs==null||ds==null)return null;
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(ivs,Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(ds,Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }
        void clear() throws Exception { ctx.getSharedPreferences(PREF,MODE_PRIVATE).edit().clear().apply(); KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(ALIAS))ks.deleteEntry(ALIAS); }
    }
}
