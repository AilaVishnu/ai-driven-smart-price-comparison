import { Route, Routes } from 'react-router-dom'
import Header from './components/Header.jsx'
import CompareTray from './components/CompareTray.jsx'
import Home from './pages/Home.jsx'
import SearchResults from './pages/SearchResults.jsx'
import ProductDetail from './pages/ProductDetail.jsx'
import Compare from './pages/Compare.jsx'
import Deals from './pages/Deals.jsx'
import Favorites from './pages/Favorites.jsx'
import History from './pages/History.jsx'
import Login from './pages/Login.jsx'
import Register from './pages/Register.jsx'
import NotFound from './pages/NotFound.jsx'

export default function App() {
  return (
    <>
      <Header />
      <main>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/search" element={<SearchResults />} />
          <Route path="/product/:id" element={<ProductDetail />} />
          <Route path="/compare" element={<Compare />} />
          <Route path="/deals" element={<Deals />} />
          <Route path="/favorites" element={<Favorites />} />
          <Route path="/history" element={<History />} />
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
      {/* Sticky across every page: the tray is only useful if it follows you
          while you browse. */}
      <CompareTray />
    </>
  )
}
