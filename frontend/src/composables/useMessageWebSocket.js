import { ref, onUnmounted } from 'vue';

function getWebSocketUrl() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws/messages`;
}

export function useMessageWebSocket(onMessage) {
  const connected = ref(false);
  let ws = null;
  let reconnectTimer = null;
  let subscribedGroupId = null;
  let retryCount = 0;

  const connect = () => {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    ws = new WebSocket(getWebSocketUrl());

    ws.onopen = () => {
      connected.value = true;
      retryCount = 0; // 连接成功，重置退避计数
      if (subscribedGroupId) {
        subscribe(subscribedGroupId);
      }
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === 'subscribed') return;
        onMessage?.(data);
      } catch (e) {
        console.warn('WebSocket 消息解析失败:', e);
      }
    };

    ws.onclose = () => {
      connected.value = false;
      // 指数退避重连：delay = min(1000 * 2^retryCount, 30000)
      const delay = Math.min(1000 * Math.pow(2, retryCount), 30000);
      retryCount++;
      reconnectTimer = setTimeout(connect, delay);
    };

    ws.onerror = () => {
      ws?.close();
    };
  };

  const subscribe = (groupId) => {
    subscribedGroupId = groupId;
    if (ws?.readyState === WebSocket.OPEN && groupId) {
      ws.send(JSON.stringify({ action: 'subscribe', groupId }));
    }
  };

  const unsubscribe = (groupId) => {
    if (ws?.readyState === WebSocket.OPEN && groupId) {
      ws.send(JSON.stringify({ action: 'unsubscribe', groupId }));
    }
    if (subscribedGroupId === groupId) {
      subscribedGroupId = null;
    }
  };

  const disconnect = () => {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    ws?.close();
    ws = null;
    connected.value = false;
  };

  onUnmounted(disconnect);

  return { connected, connect, subscribe, unsubscribe, disconnect };
}