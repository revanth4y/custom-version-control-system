import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter as Router } from "react-router-dom";

import "./index.css";
import { AuthProvider } from "./context/AuthProvider.jsx";
import ProjectRoutes from "./Routes.jsx";

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <Router>
      <AuthProvider>
        <ProjectRoutes />
      </AuthProvider>
    </Router>
  </React.StrictMode>,
);
