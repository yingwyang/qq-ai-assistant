package com.qqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合并转发消息解析测试
 * 验证 NapCat 合并转发消息能正确从 elements / message 数组中提取
 */
class ForwardMessageParsingTest {

    private ObjectMapper mapper;
    private NapCatService napCatService;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper();
        napCatService = new NapCatService();
        // Use reflection to set the objectMapper field
        var field = NapCatService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(napCatService, mapper);
    }

    @Nested
    @DisplayName("从 message 数组提取 (OneBot 11 标准格式)")
    class MessageArrayExtraction {

        @Test
        @DisplayName("能从 message 数组中提取 forward 类型消息")
        void shouldExtractFromMessageArray() {
            // 构造 OneBot 11 格式的 forward 消息
            ObjectNode payload = mapper.createObjectNode();
            ArrayNode messageArray = mapper.createArrayNode();

            ObjectNode forwardSegment = mapper.createObjectNode();
            forwardSegment.put("type", "forward");
            ObjectNode forwardData = mapper.createObjectNode();
            ArrayNode forwardContent = mapper.createArrayNode();

            ObjectNode subMsg1 = mapper.createObjectNode();
            subMsg1.put("message_id", "1001");
            subMsg1.put("raw_message", "你好");
            subMsg1.put("time", 1714000000L);
            ObjectNode sender1 = mapper.createObjectNode();
            sender1.put("user_id", 123456);
            sender1.put("nickname", "用户A");
            subMsg1.set("sender", sender1);

            ObjectNode subMsg2 = mapper.createObjectNode();
            subMsg2.put("message_id", "1002");
            subMsg2.put("raw_message", "世界");
            subMsg2.put("time", 1714000001L);
            ObjectNode sender2 = mapper.createObjectNode();
            sender2.put("user_id", 789012);
            sender2.put("nickname", "用户B");
            subMsg2.set("sender", sender2);

            forwardContent.add(subMsg1);
            forwardContent.add(subMsg2);
            forwardData.set("content", forwardContent);
            forwardSegment.set("data", forwardData);
            messageArray.add(forwardSegment);
            payload.set("message", messageArray);

            ArrayNode result = napCatService.extractForwardMessagesFromPayload(payload);

            assertNotNull(result);
            assertEquals(2, result.size());
            assertEquals("用户A", result.get(0).get("userNickname").asText());
            assertEquals("你好", result.get(0).get("content").asText());
            assertEquals("用户B", result.get(1).get("userNickname").asText());
            assertEquals("世界", result.get(1).get("content").asText());
        }

        @Test
        @DisplayName("message 数组为空时返回 null")
        void shouldReturnNullWhenMessageArrayEmpty() {
            ObjectNode payload = mapper.createObjectNode();
            payload.set("message", mapper.createArrayNode());

            ArrayNode result = napCatService.extractForwardMessagesFromPayload(payload);
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("从 elements 数组提取 (NapCat 原生格式)")
    class ElementsArrayExtraction {

        @Test
        @DisplayName("能从 elements 数组中提取 elementType=5 的 forward 消息")
        void shouldExtractFromElementsArray() {
            // NapCat 原生格式：elements 数组中 elementType=5 表示合并转发
            ObjectNode payload = mapper.createObjectNode();
            ArrayNode elements = mapper.createArrayNode();

            // 第一个元素：文本（作为对照）
            ObjectNode textElement = mapper.createObjectNode();
            textElement.put("elementType", 1);
            ObjectNode textData = mapper.createObjectNode();
            textData.put("content", "[CQ:forward,id=123456]");
            textElement.set("textElement", textData);
            elements.add(textElement);

            // 第二个元素：合并转发
            ObjectNode forwardElement = mapper.createObjectNode();
            forwardElement.put("elementType", 5);

            ObjectNode forwardData = mapper.createObjectNode();
            ArrayNode forwardContent = mapper.createArrayNode();

            ObjectNode subMsg = mapper.createObjectNode();
            subMsg.put("message_id", "2001");
            subMsg.put("raw_message", "转发内容1");
            subMsg.put("time", 1714000000L);
            ObjectNode sender = mapper.createObjectNode();
            sender.put("user_id", 111222);
            sender.put("nickname", "转发用户");
            subMsg.set("sender", sender);

            forwardContent.add(subMsg);
            forwardData.set("content", forwardContent);
            forwardElement.set("forwardElement", forwardData);
            elements.add(forwardElement);

            payload.set("elements", elements);

            // 同时也加上 message 数组为空的情况
            payload.set("message", mapper.createArrayNode());

            ArrayNode result = napCatService.extractForwardMessagesFromPayload(payload);

            assertNotNull(result, "应该从 elements 数组中提取到 forward 消息");
            assertEquals(1, result.size());
            assertEquals("转发用户", result.get(0).get("userNickname").asText());
            assertEquals("转发内容1", result.get(0).get("content").asText());
        }

        @Test
        @DisplayName("elements 数组中无 forward 元素时返回 null")
        void shouldReturnNullWhenNoForwardInElements() {
            ObjectNode payload = mapper.createObjectNode();
            ArrayNode elements = mapper.createArrayNode();

            ObjectNode textElement = mapper.createObjectNode();
            textElement.put("elementType", 1);
            ObjectNode textData = mapper.createObjectNode();
            textData.put("content", "普通文本");
            textElement.set("textElement", textData);
            elements.add(textElement);

            payload.set("elements", elements);
            payload.set("message", mapper.createArrayNode());

            ArrayNode result = napCatService.extractForwardMessagesFromPayload(payload);
            assertNull(result);
        }

        @Test
        @DisplayName("forwardElement 中 content 为空时返回 null")
        void shouldReturnNullWhenForwardContentEmpty() {
            ObjectNode payload = mapper.createObjectNode();
            ArrayNode elements = mapper.createArrayNode();

            ObjectNode forwardElement = mapper.createObjectNode();
            forwardElement.put("elementType", 5);
            ObjectNode forwardData = mapper.createObjectNode();
            forwardData.set("content", mapper.createArrayNode()); // 空数组
            forwardElement.set("forwardElement", forwardData);
            elements.add(forwardElement);

            payload.set("elements", elements);
            payload.set("message", mapper.createArrayNode());

            ArrayNode result = napCatService.extractForwardMessagesFromPayload(payload);
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("优先级：message 数组优先于 elements 数组")
    class PriorityTest {

        @Test
        @DisplayName("两个数组都有 forward 数据时，优先使用 message 数组")
        void shouldPreferMessageArray() {
            ObjectNode payload = mapper.createObjectNode();

            // message 数组中有 forward
            ArrayNode messageArray = mapper.createArrayNode();
            ObjectNode forwardSegment = mapper.createObjectNode();
            forwardSegment.put("type", "forward");
            ObjectNode forwardData = mapper.createObjectNode();
            ArrayNode messageContent = mapper.createArrayNode();
            ObjectNode msg1 = mapper.createObjectNode();
            msg1.put("message_id", "from-message-array");
            msg1.put("raw_message", "来自message数组");
            msg1.put("time", 1714000000L);
            ObjectNode s1 = mapper.createObjectNode();
            s1.put("user_id", 1);
            s1.put("nickname", "消息数组");
            msg1.set("sender", s1);
            messageContent.add(msg1);
            forwardData.set("content", messageContent);
            forwardSegment.set("data", forwardData);
            messageArray.add(forwardSegment);
            payload.set("message", messageArray);

            // elements 数组中也有 forward
            ArrayNode elements = mapper.createArrayNode();
            ObjectNode forwardElement = mapper.createObjectNode();
            forwardElement.put("elementType", 5);
            ObjectNode forwardElementData = mapper.createObjectNode();
            ArrayNode elementContent = mapper.createArrayNode();
            ObjectNode msg2 = mapper.createObjectNode();
            msg2.put("message_id", "from-elements");
            msg2.put("raw_message", "来自elements数组");
            msg2.put("time", 1714000000L);
            ObjectNode s2 = mapper.createObjectNode();
            s2.put("user_id", 2);
            s2.put("nickname", "Elements数组");
            msg2.set("sender", s2);
            elementContent.add(msg2);
            forwardElementData.set("content", elementContent);
            forwardElement.set("forwardElement", forwardElementData);
            elements.add(forwardElement);
            payload.set("elements", elements);

            ArrayNode result = napCatService.extractForwardMessagesFromPayload(payload);

            assertNotNull(result);
            assertEquals("来自message数组", result.get(0).get("content").asText());
        }

        @Test
        @DisplayName("message 数组无 forward 时回退到 elements 数组")
        void shouldFallbackToElements() {
            ObjectNode payload = mapper.createObjectNode();

            // message 数组没有 forward 类型
            ArrayNode messageArray = mapper.createArrayNode();
            ObjectNode textSegment = mapper.createObjectNode();
            textSegment.put("type", "text");
            ObjectNode textData = mapper.createObjectNode();
            textData.put("text", "普通消息");
            textSegment.set("data", textData);
            messageArray.add(textSegment);
            payload.set("message", messageArray);

            // elements 数组有 forward
            ArrayNode elements = mapper.createArrayNode();
            ObjectNode forwardElement = mapper.createObjectNode();
            forwardElement.put("elementType", 5);
            ObjectNode forwardData = mapper.createObjectNode();
            ArrayNode forwardContent = mapper.createArrayNode();
            ObjectNode subMsg = mapper.createObjectNode();
            subMsg.put("message_id", "elements-forward");
            subMsg.put("raw_message", "elements中的转发");
            subMsg.put("time", 1714000000L);
            ObjectNode sender = mapper.createObjectNode();
            sender.put("user_id", 999);
            sender.put("nickname", "Elements转发");
            subMsg.set("sender", sender);
            forwardContent.add(subMsg);
            forwardData.set("content", forwardContent);
            forwardElement.set("forwardElement", forwardData);
            elements.add(forwardElement);
            payload.set("elements", elements);

            ArrayNode result = napCatService.extractForwardMessagesFromPayload(payload);

            assertNotNull(result, "应该回退到 elements 数组提取");
            assertEquals("elements中的转发", result.get(0).get("content").asText());
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("payload 为 null 时返回 null")
        void shouldReturnNullForNullPayload() {
            ArrayNode result = napCatService.extractForwardMessagesFromPayload(null);
            assertNull(result);
        }

        @Test
        @DisplayName("两个数组都不存在时返回 null")
        void shouldReturnNullWhenNoArrays() {
            ObjectNode payload = mapper.createObjectNode();
            // 没有 message 也没有 elements

            ArrayNode result = napCatService.extractForwardMessagesFromPayload(payload);
            assertNull(result);
        }

        @Test
        @DisplayName("forward 消息中嵌套的子消息也能正确规范化")
        void shouldNormalizeNestedForwardMessages() {
            ObjectNode payload = mapper.createObjectNode();
            ArrayNode elements = mapper.createArrayNode();

            ObjectNode forwardElement = mapper.createObjectNode();
            forwardElement.put("elementType", 5);
            ObjectNode forwardData = mapper.createObjectNode();
            ArrayNode forwardContent = mapper.createArrayNode();

            // 带子消息的转发项
            ObjectNode subMsg = mapper.createObjectNode();
            subMsg.put("message_id", "nested-1");
            subMsg.put("raw_message", "外层消息");
            subMsg.put("time", 1714000000L);
            ObjectNode sender = mapper.createObjectNode();
            sender.put("user_id", 555);
            sender.put("nickname", "嵌套消息");
            subMsg.set("sender", sender);

            // 嵌套的子消息数组
            ArrayNode nestedMessages = mapper.createArrayNode();
            ObjectNode nestedMsg = mapper.createObjectNode();
            nestedMsg.put("message_id", "nested-2");
            nestedMsg.put("raw_message", "内层消息");
            nestedMsg.put("time", 1714000001L);
            ObjectNode nestedSender = mapper.createObjectNode();
            nestedSender.put("user_id", 666);
            nestedSender.put("nickname", "内层发送者");
            nestedMsg.set("sender", nestedSender);
            nestedMessages.add(nestedMsg);

            subMsg.set("message", nestedMessages);
            forwardContent.add(subMsg);
            forwardData.set("content", forwardContent);
            forwardElement.set("forwardElement", forwardData);
            elements.add(forwardElement);
            payload.set("elements", elements);
            payload.set("message", mapper.createArrayNode());

            ArrayNode result = napCatService.extractForwardMessagesFromPayload(payload);

            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("嵌套消息", result.get(0).get("userNickname").asText());
            assertEquals("外层消息", result.get(0).get("content").asText());
            assertEquals("TEXT", result.get(0).get("messageType").asText());
        }
    }
}