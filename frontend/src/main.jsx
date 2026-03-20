import React, { Suspense } from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter as Router } from "react-router-dom";
import { ThemeProvider, BaseStyles } from "@primer/react";

import "./theme/primer-vars.css";
import "./index.css";
import { gitforgeTheme } from "./theme/gitforge";
import { AuthProvider } from "./context/AuthProvider.jsx";
import ErrorBoundary from "./components/common/ErrorBoundary.jsx";
import AppRoutes from "./routes/AppRoutes.jsx";

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <ThemeProvider theme={gitforgeTheme} colorMode="night" nightScheme="dark">
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
    </ThemeProvider>
  </React.StrictMode>,
);
