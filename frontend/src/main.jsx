import React, { Suspense } from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter as Router } from "react-router-dom";
import { BaseStyles } from "@primer/react";

import "./theme/fonts.css";
import "./theme/primer-vars.css";
import "./index.css";
import { ColorModeProvider } from "./context/ColorModeProvider.jsx";
import { AuthProvider } from "./context/AuthProvider.jsx";
import ErrorBoundary from "./components/common/ErrorBoundary.jsx";
import AppRoutes from "./routes/AppRoutes.jsx";

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <ColorModeProvider>
      <BaseStyles>
        <Router>
          <AuthProvider>
            {/* Pages are code-split by route. The fallback is deliberately
                nothing: a chunk arrives in a few milliseconds on a local
                connection, and a spinner that flashes and vanishes reads as a
                fault rather than as progress. */}
            <ErrorBoundary>
              <Suspense fallback={null}>
                <AppRoutes />
              </Suspense>
            </ErrorBoundary>
          </AuthProvider>
        </Router>
      </BaseStyles>
    </ColorModeProvider>
  </React.StrictMode>,
);
