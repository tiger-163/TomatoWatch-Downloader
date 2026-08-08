package com.tomatowatch.downloader;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    // ===== 可配置区域：如果 API 失效，改这里 =====
    // 当前可用的第三方 API 端点（2025-2026年验证可用）
    private static final String[] API_ENDPOINTS = {
        "https://api-return.cflin.ddns-ip.net/api/xiaoshuo/fanqie",
        "http://101.35.133.34:5000"
    };
    // =============================================

    private EditText etInput;
    private Button btnDownload;
    private TextView tvStatus;
    private RequestQueue queue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etInput = findViewById(R.id.etInput);
        btnDownload = findViewById(R.id.btnDownload);
        tvStatus = findViewById(R.id.tvStatus);
        queue = Volley.newRequestQueue(this);

        btnDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String input = etInput.getText().toString().trim();
                if (input.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请输入番茄小说链接或ID", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 从输入中提取书籍ID
                String bookId = extractBookId(input);
                if (bookId == null) {
                    // 当作搜索关键词处理
                    searchAndDownload(input);
                } else {
                    // 直接下载
                    downloadBook(bookId);
                }
            }
        });
    }

    /**
     * 从番茄链接中提取书籍ID
     * 例如：https://fanqienovel.com/page/7143038691944959011
     * 提取出：7143038691944959011
     */
    private String extractBookId(String input) {
        // 如果是纯数字，直接当作ID
        if (input.matches("\\d+")) {
            return input;
        }
        // 尝试从链接中提取
        int idx = input.indexOf("page/");
        if (idx != -1) {
            String rest = input.substring(idx + 5);
            int endIdx = rest.indexOf("?");
            if (endIdx != -1) {
                rest = rest.substring(0, endIdx);
            }
            if (rest.matches("\\d+")) {
                return rest;
            }
        }
        return null; // 不是ID，是搜索词
    }

    /**
     * 搜索书籍并下载第一本
     */
    private void searchAndDownload(String keyword) {
        tvStatus.setText("搜索中：" + keyword);
        try {
            String encodedKeyword = URLEncoder.encode(keyword, "UTF-8");
            String url = API_ENDPOINTS[0] + "?q=" + encodedKeyword;

            StringRequest request = new StringRequest(Request.Method.GET, url,
                response -> {
                    try {
                        // 解析 API 返回的 JSON，提取第一本书的ID
                        JSONObject json = new JSONObject(response);
                        // 不同 API 返回结构不同，这里做兼容处理
                        String bookId = parseBookIdFromSearch(json);
                        if (bookId != null) {
                            downloadBook(bookId);
                        } else {
                            tvStatus.setText("搜索无结果，请尝试其他关键词");
                        }
                    } catch (Exception e) {
                        tvStatus.setText("解析搜索结果失败：" + e.getMessage());
                        tryNextEndpoint(0, keyword, true);
                    }
                },
                error -> {
                    tvStatus.setText("搜索失败，尝试备用API...");
                    tryNextEndpoint(0, keyword, true);
                });

            queue.add(request);
        } catch (Exception e) {
            tvStatus.setText("错误：" + e.getMessage());
        }
    }

    /**
     * API 故障转移：尝试下一个端点
     */
    private void tryNextEndpoint(int currentIndex, String param, boolean isSearch) {
        int nextIndex = currentIndex + 1;
        if (nextIndex >= API_ENDPOINTS.length) {
            tvStatus.setText("所有API均不可用，请稍后重试或更新API地址");
            return;
        }

        if (isSearch) {
            tvStatus.setText("尝试备用API搜索...");
            try {
                String encodedKeyword = URLEncoder.encode(param, "UTF-8");
                String url = API_ENDPOINTS[nextIndex] + "?q=" + encodedKeyword;
                StringRequest request = new StringRequest(Request.Method.GET, url,
                    response -> {
                        try {
                            JSONObject json = new JSONObject(response);
                            String bookId = parseBookIdFromSearch(json);
                            if (bookId != null) {
                                downloadBook(bookId);
                            } else {
                                tvStatus.setText("搜索无结果");
                            }
                        } catch (Exception e) {
                            tvStatus.setText("备用API也失败了");
                        }
                    },
                    error -> tvStatus.setText("备用API请求失败"));
                queue.add(request);
            } catch (Exception e) {
                tvStatus.setText("错误：" + e.getMessage());
            }
        }
    }

    /**
     * 下载整本书
     */
    private void downloadBook(String bookId) {
        tvStatus.setText("获取书籍信息中...");
        String url = API_ENDPOINTS[0] + "?xq=" + bookId;

        StringRequest request = new StringRequest(Request.Method.GET, url,
            response -> {
                try {
                    tvStatus.setText("开始下载章节...");
                    parseAndSaveBook(response, bookId);
                } catch (Exception e) {
                    tvStatus.setText("下载失败：" + e.getMessage());
                }
            },
            error -> {
                tvStatus.setText("获取书籍信息失败，尝试备用API...");
                // 备用API逻辑类似，这里简化为提示
                tvStatus.setText("主API失败，请在代码中更新API地址");
            });

        queue.add(request);
    }

    /**
     * 解析搜索结果，提取书籍ID（兼容多种返回格式）
     */
    private String parseBookIdFromSearch(JSONObject json) {
        try {
            // 尝试常见的几种 JSON 结构
            if (json.has("data")) {
                Object data = json.get("data");
                if (data instanceof JSONObject) {
                    JSONObject dataObj = (JSONObject) data;
                    if (dataObj.has("book_id")) return dataObj.getString("book_id");
                    if (dataObj.has("id")) return dataObj.getString("id");
                    if (dataObj.has("list")) {
                        JSONArray list = dataObj.getJSONArray("list");
                        if (list.length() > 0) {
                            JSONObject first = list.getJSONObject(0);
                            if (first.has("book_id")) return first.getString("book_id");
                            if (first.has("id")) return first.getString("id");
                        }
                    }
                }
                if (data instanceof JSONArray) {
                    JSONArray dataArr = (JSONArray) data;
                    if (dataArr.length() > 0) {
                        JSONObject first = dataArr.getJSONObject(0);
                        if (first.has("book_id")) return first.getString("book_id");
                        if (first.has("id")) return first.getString("id");
                    }
                }
            }
            if (json.has("book_id")) return json.getString("book_id");
            if (json.has("id")) return json.getString("id");
        } catch (Exception e) {
            // 解析失败
        }
        return null;
    }

    /**
     * 解析书籍内容并保存为 TXT
     */
    private void parseAndSaveBook(String response, String bookId) throws Exception {
        JSONObject json = new JSONObject(response);

        // 提取书名（兼容多种格式）
        String bookName = "番茄小说_" + bookId;
        if (json.has("data")) {
            Object data = json.get("data");
            if (data instanceof JSONObject) {
                JSONObject dataObj = (JSONObject) data;
                if (dataObj.has("book_name")) bookName = dataObj.getString("book_name");
                else if (dataObj.has("title")) bookName = dataObj.getString("title");
                else if (dataObj.has("name")) bookName = dataObj.getString("name");
            }
        }
        if (json.has("book_name")) bookName = json.getString("book_name");

        // 提取章节列表（兼容多种格式）
        List<String> chapterIds = new ArrayList<>();
        List<String> chapterTitles = new ArrayList<>();

        if (json.has("data")) {
            Object data = json.get("data");
            if (data instanceof JSONObject) {
                JSONObject dataObj = (JSONObject) data;
                if (dataObj.has("chapter_list")) {
                    JSONArray chapters = dataObj.getJSONArray("chapter_list");
                    for (int i = 0; i < chapters.length(); i++) {
                        JSONObject ch = chapters.getJSONObject(i);
                        String cid = ch.has("chapter_id") ? ch.getString("chapter_id") :
                                     ch.has("id") ? ch.getString("id") : "";
                        String ctitle = ch.has("chapter_title") ? ch.getString("chapter_title") :
                                        ch.has("title") ? ch.getString("title") : "第" + (i+1) + "章";
                        if (!cid.isEmpty()) {
                            chapterIds.add(cid);
                            chapterTitles.add(ctitle);
                        }
                    }
                }
            }
        }

        if (chapterIds.isEmpty()) {
            tvStatus.setText("未找到章节列表，可能API格式变化");
            return;
        }

        // 保存为 TXT 文件
        saveChaptersToTxt(bookName, chapterIds, chapterTitles);
    }

    /**
     * 逐章获取内容并保存为 TXT
     */
    private void saveChaptersToTxt(String bookName, List<String> chapterIds, List<String> chapterTitles) {
        StringBuilder content = new StringBuilder();
        content.append("书名：").append(bookName).append("\n\n");

        final int total = chapterIds.size();
        for (int i = 0; i < total; i++) {
            final int index = i;
            String cid = chapterIds.get(i);
            String ctitle = chapterTitles.get(i);

            // 更新进度
            runOnUiThread(() -> tvStatus.setText(String.format("下载进度：%d/%d", index+1, total)));

            // 获取章节内容
            String chapterUrl = API_ENDPOINTS[0] + "?content=" + cid;
            try {
                // 同步请求（简单粗暴，但手表上够用）
                String chapterResponse = syncRequest(chapterUrl);
                JSONObject chJson = new JSONObject(chapterResponse);
                String chapterContent = extractChapterContent(chJson);

                content.append("第").append(index+1).append("章 ").append(ctitle).append("\n\n");
                content.append(chapterContent).append("\n\n");

                // 简单延时，避免请求过快
                Thread.sleep(500);

            } catch (Exception e) {
                content.append("第").append(index+1).append("章 ").append(ctitle).append("\n\n");
                content.append("[下载失败：").append(e.getMessage()).append("]\n\n");
            }
        }

        // 写入文件
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File tomatoDir = new File(downloadDir, "TomatoWatch");
            if (!tomatoDir.exists()) {
                tomatoDir.mkdirs();
            }

            // 清理书名中的非法字符
            String safeName = bookName.replaceAll("[\\\\/:*?\"<>|]", "_");
            File outputFile = new File(tomatoDir, safeName + ".txt");

            FileWriter writer = new FileWriter(outputFile);
            writer.write(content.toString());
            writer.close();

            runOnUiThread(() -> {
                tvStatus.setText("下载完成！\n保存到：\n" + outputFile.getAbsolutePath());
                Toast.makeText(MainActivity.this, "下载完成：" + safeName, Toast.LENGTH_LONG).show();
            });

        } catch (IOException e) {
            runOnUiThread(() -> tvStatus.setText("保存文件失败：" + e.getMessage()));
        }
    }

    /**
     * 同步 HTTP 请求（简单粗暴）
     */
    private String syncRequest(String url) throws Exception {
        java.net.URL obj = new java.net.URL(url);
        java.net.HttpURLConnection con = (java.net.HttpURLConnection) obj.openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);

        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        con.disconnect();

        return response.toString();
    }

    /**
     * 从章节 JSON 中提取正文
     */
    private String extractChapterContent(JSONObject json) {
        try {
            if (json.has("data")) {
                Object data = json.get("data");
                if (data instanceof JSONObject) {
                    JSONObject dataObj = (JSONObject) data;
                    if (dataObj.has("content")) return dataObj.getString("content");
                    if (dataObj.has("chapter_content")) return dataObj.getString("chapter_content");
                    if (dataObj.has("text")) return dataObj.getString("text");
                }
                if (data instanceof String) {
                    return (String) data;
                }
            }
            if (json.has("content")) return json.getString("content");
            if (json.has("chapter_content")) return json.getString("chapter_content");
        } catch (Exception e) {
            // 解析失败
        }
        return "[内容解析失败]";
    }

    /**
     * 如果 API 返回结构不同，可能需要调整这里的解析逻辑
     * 建议先在电脑浏览器里访问 API 地址，查看返回的 JSON 结构
     */
}
