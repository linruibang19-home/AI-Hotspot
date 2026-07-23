import http from "k6/http";
import exec from "k6/execution";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "http://nginx";
const errorRate = new Rate("public_errors");
const endpointLatency = {
  home: new Trend("home_latency_ms", true),
  allPage: new Trend("all_page_latency_ms", true),
  list: new Trend("list_latency_ms", true),
  detail: new Trend("detail_latency_ms", true),
  search: new Trend("search_latency_ms", true),
  topics: new Trend("topics_latency_ms", true),
};
const scenarioLatency = {
  browse_1: new Trend("browse_1_latency_ms", true),
  browse_10: new Trend("browse_10_latency_ms", true),
  browse_25: new Trend("browse_25_latency_ms", true),
  browse_50: new Trend("browse_50_latency_ms", true),
};

export const options = {
  scenarios: {
    browse_1: { executor: "constant-vus", vus: 1, duration: "20s", gracefulStop: "5s" },
    browse_10: {
      executor: "constant-vus",
      vus: 10,
      duration: "30s",
      startTime: "25s",
      gracefulStop: "5s",
    },
    browse_25: {
      executor: "constant-vus",
      vus: 25,
      duration: "30s",
      startTime: "60s",
      gracefulStop: "5s",
    },
    browse_50: {
      executor: "constant-vus",
      vus: 50,
      duration: "30s",
      startTime: "95s",
      gracefulStop: "5s",
    },
  },
  thresholds: {
    public_errors: ["rate<0.01"],
    home_latency_ms: ["p(95)<1500"],
    all_page_latency_ms: ["p(95)<1500"],
    list_latency_ms: ["p(95)<500"],
    detail_latency_ms: ["p(95)<700"],
    search_latency_ms: ["p(95)<1000"],
    topics_latency_ms: ["p(95)<500"],
    browse_50_latency_ms: ["p(95)<1000"],
  },
  summaryTrendStats: ["count", "avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export function setup() {
  const contents = http.get(`${baseUrl}/api/core/api/v1/public/contents?limit=20`, {
    tags: { endpoint: "setup_contents" },
  });
  const topics = http.get(`${baseUrl}/api/core/api/v1/public/topics`, {
    tags: { endpoint: "setup_topics" },
  });
  check(contents, { "setup contents 200": (response) => response.status === 200 });
  check(topics, { "setup topics 200": (response) => response.status === 200 });
  const contentItems = contents.json("items") || [];
  const topicItems = topics.json() || [];
  return {
    contentIds: contentItems.map((item) => item.id).filter(Boolean),
    topicSlugs: topicItems.map((item) => item.slug).filter(Boolean),
  };
}

function request(name, path, metric) {
  const response = http.get(`${baseUrl}${path}`, { tags: { endpoint: name } });
  const passed = check(response, { [`${name} status 200`]: (result) => result.status === 200 });
  metric.add(response.timings.duration);
  scenarioLatency[exec.scenario.name].add(response.timings.duration);
  errorRate.add(!passed);
}

export default function (data) {
  const choice = Math.floor(Math.random() * 7);
  if (choice === 0) {
    request("home", "/", endpointLatency.home);
  } else if (choice === 1) {
    request("all_page", "/all", endpointLatency.allPage);
  } else if (choice === 2) {
    request(
      "featured",
      "/api/core/api/v1/public/contents/featured?limit=12",
      endpointLatency.list,
    );
  } else if (choice === 3) {
    request("all_contents", "/api/core/api/v1/public/contents?limit=20", endpointLatency.list);
  } else if (choice === 4 && data.contentIds.length > 0) {
    const id = data.contentIds[Math.floor(Math.random() * data.contentIds.length)];
    request("content_detail", `/api/core/api/v1/public/contents/${id}`, endpointLatency.detail);
  } else if (choice === 5) {
    request(
      "search",
      "/api/core/api/v1/public/search?query=Agent&limit=20",
      endpointLatency.search,
    );
  } else if (data.topicSlugs.length > 0) {
    const slug = data.topicSlugs[Math.floor(Math.random() * data.topicSlugs.length)];
    request(
      "topic_detail",
      `/api/core/api/v1/public/topics/${encodeURIComponent(slug)}?limit=20`,
      endpointLatency.topics,
    );
  } else {
    request("topics", "/api/core/api/v1/public/topics", endpointLatency.topics);
  }
  sleep(0.2 + Math.random() * 0.6);
}
