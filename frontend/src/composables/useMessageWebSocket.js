import { ref, onUnmounted } from 'vue';
import { getToken } from '../services/api';

function getWebSocketUrl() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const token = getToken();
  return `${protocol}//${window.location.host}/ws/messages?token=${encodeURIComponent(token || '')}`;
}

export function useMessageWebSocket(onMessage) {
  const connected = ref(false);
  let ws = null;
  let reconnectTimer = null;
  let subscribedGroupId = null;

  const connect = () => {
    const token = getToken();
    if (!token) return;

    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    ws = new WebSocket(getWebSocketUrl());

    ws.onopen = () => {
      connected.value = true;
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
      reconnectTimer = setTimeout(connect, 5000);
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
