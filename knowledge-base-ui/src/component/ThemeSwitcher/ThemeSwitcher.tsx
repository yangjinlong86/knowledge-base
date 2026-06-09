import { useAntdConfigSetter } from '@umijs/max';
import { theme } from 'antd';
import React, { useEffect, useState } from 'react';
import { CiLight } from 'react-icons/ci';
import { MdOutlineDarkMode } from 'react-icons/md';

const { darkAlgorithm } = theme;

const cyberBlueTheme = {
  algorithm: [darkAlgorithm],
  token: {
    colorPrimary: '#38d7ff',
    colorInfo: '#1677ff',
    colorSuccess: '#21f6bc',
    colorWarning: '#f7c948',
    colorError: '#ff4d7d',
    colorBgBase: '#030712',
    colorBgContainer: 'rgba(8, 24, 52, 0.78)',
    colorBgElevated: 'rgba(10, 32, 68, 0.96)',
    colorBorder: 'rgba(75, 205, 255, 0.32)',
    colorText: 'rgba(242, 250, 255, 0.96)',
    colorTextSecondary: 'rgba(200, 230, 255, 0.78)',
    colorTextTertiary: 'rgba(170, 211, 255, 0.68)',
    colorTextQuaternary: 'rgba(145, 190, 235, 0.56)',
    borderRadius: 14,
    wireframe: false,
  },
  components: {
    Button: {
      borderRadius: 18,
      controlHeight: 36,
    },
    Menu: {
      itemBg: 'transparent',
      itemColor: 'rgba(218, 240, 255, 0.82)',
      itemHoverBg: 'rgba(56, 215, 255, 0.12)',
      itemHoverColor: '#ffffff',
      itemSelectedBg: 'rgba(56, 215, 255, 0.22)',
      itemSelectedColor: '#ffffff',
      itemActiveBg: 'rgba(56, 215, 255, 0.16)',
    },
    Select: {
      optionSelectedBg: 'rgba(56, 215, 255, 0.22)',
      optionSelectedColor: '#ffffff',
      optionActiveBg: 'rgba(56, 215, 255, 0.12)',
    },
  },
};

const ThemeSwitcher: React.FC = () => {
  const [themeMode, setThemeMode] = useState<'light' | 'dark'>(
    (localStorage.getItem('vite-ui-theme') as 'light' | 'dark') || 'dark',
  );
  const antdConfigSetter = useAntdConfigSetter();

  useEffect(() => {
    antdConfigSetter({
      theme: cyberBlueTheme,
    });
    document.documentElement.setAttribute('data-theme', 'dark');
    localStorage.setItem('vite-ui-theme', 'dark');
  }, [themeMode]);

  return themeMode === 'light' ? (
    <MdOutlineDarkMode
      size={35}
      style={{ cursor: 'pointer' }}
      onClick={() => setThemeMode('dark')}
      title="切换为暗黑模式"
    />
  ) : (
    <CiLight
      size={35}
      style={{ cursor: 'pointer' }}
      onClick={() => setThemeMode('light')}
      title="切换为明亮模式"
    />
  );
};

export default ThemeSwitcher;
