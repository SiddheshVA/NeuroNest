// Lets plain modules (like api.ts) fetch a fresh Clerk session token
// without being React components themselves. ClerkTokenBridge (mounted
// inside ClerkProvider in main.tsx) keeps this pointed at the current
// getToken() from Clerk's useAuth().
type GetToken = (options?: { template?: string }) => Promise<string | null>;

let currentGetToken: GetToken = async () => null;

export function registerTokenGetter(getToken: GetToken) {
  currentGetToken = getToken;
}

export function getAuthToken() {
  // The default session token doesn't carry email/name; those are only on
  // the "neuronest" JWT template configured in the Clerk dashboard.
  return currentGetToken({ template: "neuronest" });
}
