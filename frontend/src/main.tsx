import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App.tsx";
import { ClerkProviderWithRoutes, ClerkTokenBridge } from "./ClerkProviders.tsx";
import "./globals.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <ClerkProviderWithRoutes>
        <ClerkTokenBridge>
          <App />
        </ClerkTokenBridge>
      </ClerkProviderWithRoutes>
    </BrowserRouter>
  </StrictMode>,
);
