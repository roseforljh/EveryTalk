import type { Metadata } from 'next'
import { notFound } from 'next/navigation'
import FeatureDetail from '@/components/sections/FeatureDetail'
import { featureDetails, getFeatureDetail } from '@/lib/feature-details'

export function generateStaticParams() {
  return featureDetails.map(({ slug }) => ({ slug }))
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>
}): Promise<Metadata> {
  const detail = getFeatureDetail((await params).slug)
  return {
    title: detail ? `${detail.label} | EveryTalk` : '功能详情 | EveryTalk',
  }
}

export default async function FeatureDetailPage({
  params,
}: {
  params: Promise<{ slug: string }>
}) {
  const detail = getFeatureDetail((await params).slug)
  if (!detail) notFound()

  return (
    <div className="subpage">
      <FeatureDetail detail={detail} />
    </div>
  )
}
