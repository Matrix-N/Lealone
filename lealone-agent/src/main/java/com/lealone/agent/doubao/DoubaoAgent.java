/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.agent.doubao;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.lealone.agent.CodeAgentBase;
import com.lealone.common.exceptions.DbException;
import com.lealone.orm.json.JsonArray;
import com.lealone.orm.json.JsonObject;

//调用LLM的api是低频操作，并且LLM的处理速度很慢，所以直接用HttpURLConnection发送请求处理响应即可
public class DoubaoAgent extends CodeAgentBase {

    public DoubaoAgent() {
        super("doubao");
    }

    @Override
    public void init(String[] args) {
        super.init(args);
        afterInit();
    }

    @Override
    public void init(Map<String, String> config) {
        super.init(config);
        afterInit();
    }

    private void afterInit() {
        if (model == null)
            model = "doubao-seed-2-0-pro-260215";
        if (url == null)
            // url = "https://ark.cn-beijing.volces.com/api/v3";
            // url = "https://ark.cn-beijing.volces.com/api/v3/chat/completions";
            url = "https://ark.cn-beijing.volces.com/api/v3/responses";
    }

    // public ArkService getArkService() {
    // return ArkService.builder().apiKey(apiKey).baseUrl(url).build();
    // }
    //
    // public String generateJavaCode(String userPrompt) {
    // ArkService arkService = getArkService();
    //
    // CreateResponsesRequest request = CreateResponsesRequest.builder().model(model)
    // .input(ResponsesInput.builder().stringValue(userPrompt).build())
    // // Manually disable deep thinking
    // .thinking(ResponsesThinking.builder().type(ResponsesConstants.THINKING_TYPE_DISABLED)
    // .build())
    // .build();
    //
    // ResponseObject resp = arkService.createResponse(request);
    // String javaCode = "";
    // for (BaseItem item : resp.getOutput()) {
    // if (ResponsesConstants.ITEM_TYPE_MESSAGE.equals(item.getType())) {
    // ItemOutputMessage message = (ItemOutputMessage) item;
    // javaCode = ((OutputContentItemText) message.getContent().get(0)).getText();
    // break;
    // }
    // }
    //
    // arkService.shutdownExecutor();
    // return javaCode;
    // }

    private static final String promptPrefix = "请根据需求生成java代码实现类，不要生成接口，代码采用驼峰格式，" + //
            "只输出纯代码，不要任何解释，不要```java标记，不要多余文字：";

    @Override
    public String getPromptPrefix() {
        return promptPrefix;
    }

    @Override // Responses API
    public String send(String userPrompt, AtomicReference<String> previousResponseId) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setDoOutput(true);
            // connection.setConnectTimeout(10000);
            // connection.setReadTimeout(30000);

            JsonObject reqBody = new JsonObject();
            reqBody.put("model", model);
            reqBody.put("input", userPrompt);
            if (previousResponseId != null && previousResponseId.get() != null
                    && !previousResponseId.get().isBlank()) {
                reqBody.put("previous_response_id", previousResponseId.get());
            }
            reqBody.put("stream", false);
            reqBody.put("thinking", new JsonObject().put("type", "disabled"));

            try (OutputStream os = connection.getOutputStream()) {
                byte[] data = reqBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(data);
            }

            int code = connection.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    code == 200 ? connection.getInputStream() : connection.getErrorStream(),
                    StandardCharsets.UTF_8));
            StringBuilder resp = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                resp.append(line);
            }
            br.close();
            connection.disconnect();
            JsonObject json = new JsonObject(resp.toString());
            if (previousResponseId != null) {
                previousResponseId.set(json.getString("id"));
            }
            List<?> output = (List<?>) json.getMap().get("output");
            Map<?, ?> message = (Map<?, ?>) output.get(0);
            List<?> content = (List<?>) message.get("content");
            Map<?, ?> outputText = (Map<?, ?>) content.get(0);
            return (String) outputText.get("text");
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    // 对话(Chat) API
    public String chat(String userPrompt) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setDoOutput(true);
            JsonArray messages = new JsonArray();
            JsonObject message = new JsonObject();
            message.put("role", "user");
            message.put("content", userPrompt);
            messages.add(message);
            JsonObject json = new JsonObject();
            json.put("model", model);
            json.put("messages", messages);
            json.put("thinking", new JsonObject().put("type", "disabled"));
            try (OutputStream out = connection.getOutputStream()) {
                out.write(json.encode().getBytes("UTF-8"));
            }
            try (InputStream is = connection.getInputStream()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String inputLine;
                StringBuilder response = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                json = new JsonObject(response.toString());
                List<?> choices = (List<?>) json.getMap().get("choices");
                Map<?, ?> choice = (Map<?, ?>) choices.get(0);
                Map<?, ?> responseMessage = (Map<?, ?>) choice.get("message");
                String content = (String) responseMessage.get("content");
                return content;
            }
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }
}
