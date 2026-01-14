import type { Meta, StoryObj } from '@storybook/nextjs-vite'
import Checkbox from "./Checkbox";

const meta = {
    title: 'Common/Checkbox',
    component: Checkbox,
    parameters: {
        layout: 'centered'
    },
} satisfies Meta<typeof Checkbox>;

export default meta;
type Story = StoryObj<typeof meta>

export const Default: Story = {
    args: {
        label: '라벨 텍스트'
    }
}

export const Checked: Story = {
    args: {
        label: '라벨 텍스트',
        checked: true
    }
}

export const Unchecked: Story = {
    args: {
        label: '라벨 텍스트',
        checked: false
    }
}