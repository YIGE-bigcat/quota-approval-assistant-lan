import http from '@ohos.net.http';
import { BRIDGE_BASE_URL, BRIDGE_TOKEN } from './BridgeConfig';
import { WatchStatus } from './Models';

function endpoint(path: string): string {
  const separator = path.includes('?') ? '&' : '?';
  return `${BRIDGE_BASE_URL}${path}${separator}token=${encodeURIComponent(BRIDGE_TOKEN)}`;
}

function responseText(result: string | object | ArrayBuffer): string {
  if (typeof result === 'string') {
    return result;
  }
  if (result instanceof ArrayBuffer) {
    return String.fromCharCode(...new Uint8Array(result));
  }
  return JSON.stringify(result);
}

async function requestJson<T>(url: string, method: http.RequestMethod, body?: object): Promise<T> {
  const client = http.createHttp();
  try {
    const response = await client.request(url, {
      method,
      connectTimeout: 5000,
      readTimeout: 8000,
      header: {
        'Accept': 'application/json',
        'Content-Type': 'application/json; charset=utf-8'
      },
      extraData: body ? JSON.stringify(body) : undefined
    });

    const statusCode = Number(response.responseCode);
    const text = responseText(response.result as string | object | ArrayBuffer);
    if (statusCode < 200 || statusCode >= 300) {
      throw new Error(`HTTP ${statusCode}`);
    }
    return JSON.parse(text) as T;
  } finally {
    client.destroy();
  }
}

export async function fetchWatchStatus(): Promise<WatchStatus> {
  return requestJson<WatchStatus>(endpoint('/api/watch/status'), http.RequestMethod.GET);
}

export async function decideApproval(id: string, decision: 'allow' | 'deny'): Promise<void> {
  await requestJson<object>(
    endpoint(`/api/watch/approvals/${id}/decision`),
    http.RequestMethod.POST,
    { decision }
  );
}
