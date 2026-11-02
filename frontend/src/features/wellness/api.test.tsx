// The API module against a mocked apiFetch (Vitest): the what-if preview always asks the backend not to save.
import { beforeEach, describe, expect, test, vi } from "vitest";

import { apiFetch } from "@/lib/api";
import { deleteCheckin, latestCheckin, previewCheckin, submitCheckin } from "./api.ts";
import { WellnessApiError } from "./errors.ts";
import { REQUEST, response } from "./test-fixtures.ts";

vi.mock("@/lib/api", () => ({ apiFetch: vi.fn() }));
const fetchMock = vi.mocked(apiFetch);

const json = (status: number, body: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

beforeEach(() => vi.clearAllMocks());

describe("wellness api", () => {
  test("previewCheckin always sends save=false; submitCheckin never does", async () => {
    fetchMock.mockImplementation(async () => json(200, response("Low")));
    await previewCheckin(REQUEST);
    await submitCheckin(REQUEST);
    expect(fetchMock.mock.calls[0][0]).toBe("/wellness/checkin?save=false");
    expect(fetchMock.mock.calls[1][0]).toBe("/wellness/checkin");
    for (const [, init] of fetchMock.mock.calls) {
      expect(init?.method).toBe("POST");
      expect(JSON.parse(String(init?.body))).toEqual(REQUEST);
    }
  });

  test("errors carry status, code and field messages; network failures are status 0", async () => {
    fetchMock.mockResolvedValueOnce(
      json(400, { error: "bad", code: "WELLNESS_INVALID_INPUT", fields: [{ field: "pressure", message: "too high" }] }),
    );
    await expect(submitCheckin(REQUEST)).rejects.toMatchObject({
      status: 400,
      code: "WELLNESS_INVALID_INPUT",
      fields: [{ field: "pressure", message: "too high" }],
    });
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const error = await submitCheckin(REQUEST).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(WellnessApiError);
    expect((error as WellnessApiError).status).toBe(0);
  });

  test("latest returns null on 204; ids are URL-encoded", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    expect(await latestCheckin()).toBeNull();
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await deleteCheckin("a/b");
    expect(fetchMock.mock.calls[1][0]).toBe("/wellness/checkins/a%2Fb");
  });
});
