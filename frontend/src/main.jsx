import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter as Router } from "react-router-dom";
import { ThemeProvider, BaseStyles } from "@primer/react";

import "./theme/primer-vars.css";
import "./index.css";
import { gitforgeTheme } from "./theme/gitforge";
import { AuthProvider } from "./context/AuthProvider.jsx";
import AppRoutes from "./routes/AppRoutes.jsx";

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <ThemeProvider theme={gitforgeTheme} colorMode="night" nightScheme="dark">
      <BaseStyles>
        <Router>
          <AuthProvider>
            <AppRoutes />
          </AuthProvider>
        </Router>
      </BaseStyles>
    </ThemeProvider>
  </React.StrictMode>,
);
